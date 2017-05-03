/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.codec.multipart;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.synchronoss.cloud.nio.multipart.Multipart;
import org.synchronoss.cloud.nio.multipart.MultipartContext;
import org.synchronoss.cloud.nio.multipart.MultipartUtils;
import org.synchronoss.cloud.nio.multipart.NioMultipartParser;
import org.synchronoss.cloud.nio.multipart.NioMultipartParserListener;
import org.synchronoss.cloud.nio.stream.storage.StreamStorage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpInputMessage;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.util.Assert;

/**
 * {@code HttpMessageReader} for parsing {@code "multipart/form-data"} requests
 * to a stream of {@link Part}'s using the Synchronoss NIO Multipart library.
 *
 * <p>This reader can be provided to {@link MultipartHttpMessageReader} in order
 * to aggregate all parts into a Map.
 *
 * @author Sebastien Deleuze
 * @author Rossen Stoyanchev
 * @author Arjen Poutsma
 * @since 5.0
 * @see <a href="https://github.com/synchronoss/nio-multipart">Synchronoss NIO Multipart</a>
 * @see MultipartHttpMessageReader
 */
public class SynchronossPartHttpMessageReader implements HttpMessageReader<Part> {

	private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();


	@Override
	public List<MediaType> getReadableMediaTypes() {
		return Collections.singletonList(MediaType.MULTIPART_FORM_DATA);
	}

	@Override
	public boolean canRead(ResolvableType elementType, MediaType mediaType) {
		return Part.class.equals(elementType.resolve(Object.class)) &&
				(mediaType == null || MediaType.MULTIPART_FORM_DATA.isCompatibleWith(mediaType));
	}


	@Override
	public Flux<Part> read(ResolvableType elementType, ReactiveHttpInputMessage message,
			Map<String, Object> hints) {

		return Flux.create(new SynchronossPartGenerator(message, this.bufferFactory));
	}


	@Override
	public Mono<Part> readMono(ResolvableType elementType, ReactiveHttpInputMessage message,
			Map<String, Object> hints) {

		return Mono.error(new UnsupportedOperationException(
				"This reader does not support reading a single element."));
	}


	/**
	 * Consume and feed input to the Synchronoss parser, then adapt parser
	 * output events to {@code Flux<Sink<Part>>}.
	 */
	private static class SynchronossPartGenerator implements Consumer<FluxSink<Part>> {

		private final ReactiveHttpInputMessage inputMessage;

		private final DataBufferFactory bufferFactory;


		SynchronossPartGenerator(ReactiveHttpInputMessage inputMessage, DataBufferFactory factory) {
			this.inputMessage = inputMessage;
			this.bufferFactory = factory;
		}


		@Override
		public void accept(FluxSink<Part> emitter) {

			MultipartContext context = createMultipartContext();
			NioMultipartParserListener listener = new FluxSinkAdapterListener(emitter, this.bufferFactory);
			NioMultipartParser parser = Multipart.multipart(context).forNIO(listener);

			this.inputMessage.getBody().subscribe(buffer -> {
				byte[] resultBytes = new byte[buffer.readableByteCount()];
				buffer.read(resultBytes);
				try {
					parser.write(resultBytes);
				}
				catch (IOException ex) {
					listener.onError("Exception thrown providing input to the parser", ex);
				}
			}, (ex) -> {
				try {
					listener.onError("Request body input error", ex);
					parser.close();
				}
				catch (IOException ex2) {
					listener.onError("Exception thrown while closing the parser", ex2);
				}
			}, () -> {
				try {
					parser.close();
				}
				catch (IOException ex) {
					listener.onError("Exception thrown while closing the parser", ex);
				}
			});

		}

		private MultipartContext createMultipartContext() {
			HttpHeaders headers = this.inputMessage.getHeaders();
			String contentType = headers.getContentType().toString();
			int contentLength = Math.toIntExact(headers.getContentLength());
			String charset = headers.getFirst(HttpHeaders.ACCEPT_CHARSET);
			return new MultipartContext(contentType, contentLength, charset);
		}


	}
	/**
	 * Listen for parser output and adapt to {@code Flux<Sink<Part>>}.
	 */
	private static class FluxSinkAdapterListener implements NioMultipartParserListener {

		private final FluxSink<Part> sink;

		private final DataBufferFactory bufferFactory;

		private final AtomicInteger terminated = new AtomicInteger(0);


		FluxSinkAdapterListener(FluxSink<Part> sink, DataBufferFactory bufferFactory) {
			this.sink = sink;
			this.bufferFactory = bufferFactory;
		}


		@Override
		public void onPartFinished(StreamStorage storage, Map<String, List<String>> headers) {
			HttpHeaders httpHeaders = new HttpHeaders();
			httpHeaders.putAll(headers);
			Part part = MultipartUtils.getFileName(httpHeaders) != null ?
					new SynchronossFilePart(httpHeaders, storage, this.bufferFactory) :
					new DefaultSynchronossPart(httpHeaders, storage, this.bufferFactory);
			this.sink.next(part);
		}

		@Override
		public void onFormFieldPartFinished(String name, String value, Map<String, List<String>> headers) {
			HttpHeaders httpHeaders = new HttpHeaders();
			httpHeaders.putAll(headers);
			this.sink.next(new SynchronossFormFieldPart(httpHeaders, this.bufferFactory, value));
		}

		@Override
		public void onError(String message, Throwable cause) {
			if (this.terminated.getAndIncrement() == 0) {
				this.sink.error(new RuntimeException(message, cause));
			}
		}

		@Override
		public void onAllPartsFinished() {
			if (this.terminated.getAndIncrement() == 0) {
				this.sink.complete();
			}
		}

		@Override
		public void onNestedPartStarted(Map<String, List<String>> headersFromParentPart) {
		}

		@Override
		public void onNestedPartFinished() {
		}
	}


	private static abstract class AbstractSynchronossPart implements Part {

		private final HttpHeaders headers;

		private final DataBufferFactory bufferFactory;


		AbstractSynchronossPart(HttpHeaders headers, DataBufferFactory bufferFactory) {
			Assert.notNull(headers, "HttpHeaders is required");
			Assert.notNull(bufferFactory, "'bufferFactory' is required");
			this.headers = headers;
			this.bufferFactory = bufferFactory;
		}


		@Override
		public String getName() {
			return MultipartUtils.getFieldName(this.headers);
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.headers;
		}

		protected DataBufferFactory getBufferFactory() {
			return this.bufferFactory;
		}
	}

	private static class DefaultSynchronossPart extends AbstractSynchronossPart {

		private final StreamStorage storage;


		DefaultSynchronossPart(HttpHeaders headers, StreamStorage storage, DataBufferFactory factory) {
			super(headers, factory);
			Assert.notNull(storage, "'storage' is required");
			this.storage = storage;
		}


		@Override
		public Flux<DataBuffer> getContent() {
			InputStream inputStream = this.storage.getInputStream();
			return DataBufferUtils.read(inputStream, getBufferFactory(), 4096);
		}

		protected StreamStorage getStorage() {
			return this.storage;
		}
	}

	private static class SynchronossFilePart extends DefaultSynchronossPart implements FilePart {


		public SynchronossFilePart(HttpHeaders headers, StreamStorage storage, DataBufferFactory factory) {
			super(headers, storage, factory);
		}


		@Override
		public String getFilename() {
			return MultipartUtils.getFileName(getHeaders());
		}

		@Override
		public Mono<Void> transferTo(File destination) {
			ReadableByteChannel input = null;
			FileChannel output = null;
			try {
				input = Channels.newChannel(getStorage().getInputStream());
				output = new FileOutputStream(destination).getChannel();

				long size = (input instanceof FileChannel ? ((FileChannel) input).size() : Long.MAX_VALUE);
				long totalWritten = 0;
				while (totalWritten < size) {
					long written = output.transferFrom(input, totalWritten, size - totalWritten);
					if (written <= 0) {
						break;
					}
					totalWritten += written;
				}
			}
			catch (IOException ex) {
				return Mono.error(ex);
			}
			finally {
				if (input != null) {
					try {
						input.close();
					}
					catch (IOException ignored) {
					}
				}
				if (output != null) {
					try {
						output.close();
					}
					catch (IOException ignored) {
					}
				}
			}
			return Mono.empty();
		}
	}

	private static class SynchronossFormFieldPart extends AbstractSynchronossPart implements FormFieldPart {

		private final String content;


		SynchronossFormFieldPart(HttpHeaders headers, DataBufferFactory bufferFactory, String content) {
			super(headers, bufferFactory);
			this.content = content;
		}


		@Override
		public String getValue() {
			return this.content;
		}

		@Override
		public Flux<DataBuffer> getContent() {
			byte[] bytes = this.content.getBytes(getCharset());
			DataBuffer buffer = getBufferFactory().allocateBuffer(bytes.length);
			buffer.write(bytes);
			return Flux.just(buffer);
		}

		private Charset getCharset() {
			return Optional.ofNullable(MultipartUtils.getCharEncoding(getHeaders()))
					.map(Charset::forName).orElse(StandardCharsets.UTF_8);
		}
	}

}
