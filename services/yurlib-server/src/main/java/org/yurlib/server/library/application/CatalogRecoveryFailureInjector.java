package org.yurlib.server.library.application;

@FunctionalInterface
public interface CatalogRecoveryFailureInjector {

    void afterAssociationMoves();
}
