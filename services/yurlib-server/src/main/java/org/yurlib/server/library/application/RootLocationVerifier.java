package org.yurlib.server.library.application;

public interface RootLocationVerifier {

    VerifiedRootLocation verify(String mountAlias, String relativePath, String identityToken);

    record VerifiedRootLocation(String normalizedRelativePath, String identityDigest) {}
}
