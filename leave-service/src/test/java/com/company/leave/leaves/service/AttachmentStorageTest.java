package com.company.leave.leaves.service;

import com.company.leave.common.api.ApiException;
import com.company.leave.leaves.config.LeaveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests (no Spring context, no database) for storage rules and CSV escaping. */
class AttachmentStorageTest {

    private static final byte[] PDF = "%PDF-1.7 body".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1};

    @TempDir
    Path dir;

    private AttachmentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new AttachmentStorage(new LeaveProperties(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 7, 3,
                dir.resolve("uploads").toString(), 1, "http://identity", "key", "secret"));
    }

    @Test
    void storesAllowedTypesUnderRandomNamesAndLoadsThemBack() throws IOException {
        String pdf = storage.store(new MockMultipartFile("a", "Report 1.PDF", "application/pdf", PDF));
        String png = storage.store(new MockMultipartFile("a", "x.png", "image/png", PNG));
        String jpg = storage.store(new MockMultipartFile("a", "x.jpeg", "image/jpeg", JPG));
        assertTrue(pdf.endsWith(".pdf") && png.endsWith(".png") && jpg.endsWith(".jpeg"));
        assertFalse(pdf.contains("Report"), "client file name must not be used");
        assertArrayEquals(PDF, storage.load(pdf).getContentAsByteArray());
        assertEquals("application/pdf", AttachmentStorage.contentTypeOf(pdf));
        assertEquals("image/png", AttachmentStorage.contentTypeOf(png));

        storage.delete(pdf);
        assertEquals(404, assertThrows(ApiException.class, () -> storage.load(pdf)).getStatus().value());
    }

    @Test
    void rejectsEmptyOversizedAndDisallowedFiles() {
        assertValidation(new MockMultipartFile("a", "x.pdf", "application/pdf", new byte[0]));
        assertValidation(new MockMultipartFile("a", "big.pdf", "application/pdf", oversized()));
        assertValidation(new MockMultipartFile("a", "x.exe", "application/pdf", PDF));
        assertValidation(new MockMultipartFile("a", "x.pdf", "text/html", PDF));
        assertValidation(new MockMultipartFile("a", "x.pdf", "application/pdf", PNG));
        assertValidation(new MockMultipartFile("a", "noextension", "application/pdf", PDF));
        assertValidation(null);
    }

    @Test
    void pathTraversalIsNotResolved() throws IOException {
        Files.writeString(dir.resolve("secret.txt"), "top secret");
        for (String evil : new String[] {"../secret.txt", "..", "../../etc/passwd", "/etc/passwd", "", " "}) {
            ApiException ex = assertThrows(ApiException.class, () -> storage.load(evil), evil);
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        }
        assertThrows(ApiException.class, () -> storage.load(null));
        storage.delete("../secret.txt");
        assertTrue(Files.exists(dir.resolve("secret.txt")), "delete must not escape the upload directory");
    }

    @Test
    void csvCellsAreQuotedAndGuardedAgainstFormulaInjection() {
        assertEquals("", ReportService.cell(null));
        assertEquals("plain", ReportService.cell("plain"));
        assertEquals("\"a,b\"", ReportService.cell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", ReportService.cell("say \"hi\""));
        assertEquals("\"line1\nline2\"", ReportService.cell("line1\nline2"));
        assertEquals("'=1+1", ReportService.cell("=1+1"));
        assertEquals("'+cmd", ReportService.cell("+cmd"));
        assertEquals("'-cmd", ReportService.cell("-cmd"));
        assertEquals("'@SUM(A1)", ReportService.cell("@SUM(A1)"));
        assertEquals("\"'=a,b\"", ReportService.cell("=a,b"));
        assertEquals("2025-01-06", ReportService.cell(java.time.LocalDate.of(2025, 1, 6)));
    }

    private void assertValidation(MockMultipartFile file) {
        ApiException ex = assertThrows(ApiException.class, () -> storage.store(file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
    }

    private static byte[] oversized() {
        byte[] bytes = new byte[1024 * 1024 + 1];
        System.arraycopy(PDF, 0, bytes, 0, PDF.length);
        return bytes;
    }

    // keeps the Resource import meaningful for readers: loaded files are regular Resources
    @Test
    void loadedAttachmentIsReadable() throws IOException {
        String stored = storage.store(new MockMultipartFile("a", "x.pdf", "application/pdf", PDF));
        Resource resource = storage.load(stored);
        assertTrue(resource.exists() && resource.isReadable());
    }
}
