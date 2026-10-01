# Isolated Document Worker Third-Party Notices

The isolated worker distributes these Apache License 2.0 components for PDF metadata processing:

- Apache PDFBox 3.0.8 (`pdfbox`, `pdfbox-io`, and `fontbox`), Copyright The Apache Software Foundation.
- Apache Commons Logging 1.3.6, Copyright The Apache Software Foundation.

The original dependency JARs are distributed unchanged in the worker image. Their `META-INF/LICENSE` and `META-INF/NOTICE` resources remain inside each JAR. Source, license, and notice material is available from the [Apache PDFBox project](https://pdfbox.apache.org/) and [Apache Commons Logging project](https://commons.apache.org/proper/commons-logging/).

Optional PDFBox image codecs, Preflight, examples, command-line tools, and Bouncy Castle are not included.

The runtime image also installs DjVuLibre 3.5.30 from Alpine Linux for bounded DjVu page rendering.
DjVuLibre is licensed under GPL-2.0-only; its license and corresponding-source offer are provided by the Alpine package and project distribution.
