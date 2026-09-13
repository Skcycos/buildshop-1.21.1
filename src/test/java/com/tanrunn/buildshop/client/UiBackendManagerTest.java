package com.tanrunn.buildshop.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UiBackendManagerTest {
    @Test
    void invalidConfigurationUsesAuiAsTheDefault() {
        assertEquals("aui", UiBackendManager.normalize("not-a-backend"));
        assertEquals("aui", UiBackendManager.normalize(null));
        assertEquals("ldlib2", UiBackendManager.normalize(" LDLib2 "));
    }

    @Test
    void configuredBackendWinsWhenAvailable() {
        assertEquals("ldlib2", UiBackendManager.selectAvailableBackend("ldlib2", true, true));
        assertEquals("aui", UiBackendManager.selectAvailableBackend("aui", true, true));
    }

    @Test
    void missingConfiguredBackendFallsBackToTheOtherLibrary() {
        assertEquals("ldlib2", UiBackendManager.selectAvailableBackend("aui", false, true));
        assertEquals("aui", UiBackendManager.selectAvailableBackend("ldlib2", true, false));
    }

    @Test
    void noUiLibrariesProducesTheSafeMissingState() {
        assertNull(UiBackendManager.selectAvailableBackend("aui", false, false));
        assertNull(UiBackendManager.selectAvailableBackend("ldlib2", false, false));
    }
}
