# Isolated Document Worker Third-Party Notices

The isolated worker distributes these Apache License 2.0 components for PDF metadata processing:

- Apache PDFBox 3.0.8 (`pdfbox`, `pdfbox-io`, and `fontbox`), Copyright The Apache Software Foundation.
- Apache Commons Logging 1.3.6, Copyright The Apache Software Foundation.

The original dependency JARs are distributed unchanged in the worker image. Their `META-INF/LICENSE` and `META-INF/NOTICE` resources remain inside each JAR. Source, license, and notice material is available from the [Apache PDFBox project](https://pdfbox.apache.org/) and [Apache Commons Logging project](https://commons.apache.org/proper/commons-logging/).

Optional PDFBox image codecs, Preflight, examples, command-line tools, and Bouncy Castle are not included.

The runtime image also installs DjVuLibre 3.5.28 from Ubuntu for bounded DjVu page rendering.
DjVuLibre is licensed under GPL-2.0-only; its license and corresponding-source offer are provided by the Ubuntu package and project distribution.

## calibre 9.15.0

The worker distributes the unmodified official calibre Linux binary bundle for fixed-route FB2/MOBI to EPUB conversion. calibre is licensed under GPL-3.0-only. Its source, copyright, and bundled third-party license inventory are available from <https://github.com/kovidgoyal/calibre/tree/v9.15.0> and remain included in the official bundle.

The Docker build downloads the x86-64 or ARM64 artifact from <https://download.calibre-ebook.com/9.15.0/> and verifies the publisher's SHA-512 digest before extraction. Yurlib invokes only `ebook-convert` with fixed arguments inside the no-egress worker container.
