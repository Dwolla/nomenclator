# Builds the Nomenclator native-image binary and packages it on distroless.
# The build context holds only nomenclator.jar (scripts/build-image stages it). The native-image flags live in the
# jar's META-INF/native-image/com.dwolla/nomenclator/native-image.properties, so this file adds none.
# Native builds only: build each architecture's image on that architecture.

FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:25.0.5.0-Final-java25@sha256:4af907423955d43beb791ee247516e13128fc880b4e58f6a66171a90f850e1fb AS toolchain

# The native-image toolchain, apart from the jar so that scripts/native-image-metadata can run the tracing agent in
# exactly this toolchain (docker buildx build --target toolchain) without building the binary first.
# --static-nolibc links libz.a statically. The builder image lacks it and UBI ships no zlib-static package, so
# build zlib from source.
USER root
ARG ZLIB=1.3.1
ARG ZLIB_SHA256=9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23
RUN curl -fsSLo /tmp/zlib.tgz https://github.com/madler/zlib/releases/download/v${ZLIB}/zlib-${ZLIB}.tar.gz \
 && echo "${ZLIB_SHA256}  /tmp/zlib.tgz" | sha256sum -c - \
 && tar xzf /tmp/zlib.tgz -C /tmp \
 && cd /tmp/zlib-${ZLIB} \
 && CFLAGS="-O2 -fPIC" ./configure --static \
 && make -j"$(nproc)" \
 && cp libz.a /usr/lib64/ \
 && rm -rf /tmp/zlib*

USER 1001

FROM toolchain AS builder
WORKDIR /build
COPY --chown=1001 nomenclator.jar .
RUN native-image -jar nomenclator.jar \
 && mkdir out \
 && mv nomenclator out/ \
 && find . -maxdepth 1 -name '*.so' -exec mv -t out/ {} +

FROM gcr.io/distroless/base-debian12:nonroot@sha256:7f0c72cd138b442ae0deeb69c08b1acf5525439ba251a49ad93c320a061567e5

LABEL org.opencontainers.image.licenses=MIT

# native-image emits JDK shared libraries next to the binary when the image reaches code that needs them, and the
# binary loads them from there. Ship exactly what it produced; the builder stage gathered the binary and any such
# libraries into /build/out.
COPY --from=builder /build/out/ /app/

USER 65532:65532
ENTRYPOINT ["/app/nomenclator"]
