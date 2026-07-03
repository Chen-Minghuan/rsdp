package com.rsdp.service.storage;

import com.rsdp.config.properties.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LocalStorageService} 单元测试。
 */
class LocalStorageServiceTest {

    private LocalStorageService localStorageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setType("local");
        properties.setLocalPath(tempDir.toString());
        localStorageService = new LocalStorageService(properties);
    }

    @Test
    void store_shouldSaveFileAndReturnObjectKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );

        String objectKey = localStorageService.store(file, "images/IMG-001.jpg");

        assertThat(objectKey).isEqualTo("images/IMG-001.jpg");
        assertThat(Files.exists(tempDir.resolve("images/IMG-001.jpg"))).isTrue();
    }

    @Test
    void get_shouldReturnFileStream() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        localStorageService.store(file, "images/IMG-002.jpg");

        try (InputStream inputStream = localStorageService.get("images/IMG-002.jpg")) {
            assertThat(new String(inputStream.readAllBytes())).isEqualTo("fake-image");
        }
    }

    @Test
    void exists_shouldReturnTrueForExistingFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        localStorageService.store(file, "images/IMG-003.jpg");

        assertThat(localStorageService.exists("images/IMG-003.jpg")).isTrue();
        assertThat(localStorageService.exists("images/IMG-NOTEXIST.jpg")).isFalse();
    }

    @Test
    void delete_shouldRemoveFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        localStorageService.store(file, "images/IMG-004.jpg");

        localStorageService.delete("images/IMG-004.jpg");

        assertThat(localStorageService.exists("images/IMG-004.jpg")).isFalse();
    }

    @Test
    void get_shouldThrowWhenFileNotExists() {
        assertThatThrownBy(() -> localStorageService.get("images/IMG-NOTEXIST.jpg"))
            .isInstanceOf(Exception.class);
    }

    @Test
    void resolvePath_shouldAllowAbsolutePathInsideRoot() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );
        String absoluteKey = tempDir.resolve("images/IMG-ABS.jpg").toString();

        String objectKey = localStorageService.store(file, absoluteKey);

        assertThat(objectKey).isEqualTo(absoluteKey);
        assertThat(Files.exists(tempDir.resolve("images/IMG-ABS.jpg"))).isTrue();
    }

    @Test
    void resolvePath_shouldRejectAbsolutePathOutsideRoot() {
        Path outsideDir = tempDir.getParent().resolve("outside");
        String absoluteKey = outsideDir.resolve("evil.jpg").toString();

        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );

        assertThatThrownBy(() -> localStorageService.store(file, absoluteKey))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("路径越界");
    }

    @Test
    void resolvePath_shouldRejectRelativePathTraversal() {
        MockMultipartFile file = new MockMultipartFile(
            "image", "chair.jpg", "image/jpeg", "fake-image".getBytes()
        );

        assertThatThrownBy(() -> localStorageService.store(file, "../evil.jpg"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("路径越界");
    }
}
