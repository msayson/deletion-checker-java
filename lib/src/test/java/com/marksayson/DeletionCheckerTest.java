package com.marksayson;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DeletionCheckerTest {
    @Test
    void isDeletedThrowsUntilDatasetLoadingIsImplemented() {
        final DeletionChecker checker = new DeletionChecker();
        assertThrows(
                UnsupportedOperationException.class,
                () -> checker.isDeleted("User", "123"));
    }

    @Test
    void filterThrowsUntilDatasetLoadingIsImplemented() {
        final DeletionChecker checker = new DeletionChecker();
        assertThrows(
                UnsupportedOperationException.class,
                () -> checker.filter("User", List.of("123"), id -> id));
    }
}
