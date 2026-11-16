FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:25.0.5.0-Final-java25@sha256:4af907423955d43beb791ee247516e13128fc880b4e58f6a66171a90f850e1fb

# The native-image toolchain. build.sbt's toolchainImage builds it, and sbt-native-packager runs native-image in it;
# scripts/native-image-metadata runs the tracing agent in it.
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
