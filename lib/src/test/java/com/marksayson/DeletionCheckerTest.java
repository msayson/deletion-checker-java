package com.marksayson;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DeletionCheckerTest {
    @Test
    void isDeletedReturnsFalseForUntrackedEntityId() {
        final DeletionChecker deletionChecker = new DeletionChecker();
        assertFalse(
                deletionChecker.isDeleted("User", "123"),
                "isDeleted should return 'false' for untracked entity ID");
    }
}
