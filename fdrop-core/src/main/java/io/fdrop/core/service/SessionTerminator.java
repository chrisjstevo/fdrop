package io.fdrop.core.service;

/** Ends a user's live sessions in one protocol; HTTP and SFTP each register one. */
@FunctionalInterface
public interface SessionTerminator {

    void terminate(String sub);
}
