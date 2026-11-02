package net.chimera.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A material pump's uploads share one recording: it begins at the first upload of an open batch and
 * submits once at close with each written image, so no upload submits or waits on its own.
 */
final class TextureUploadBatchTest {
    private static final class FakeQueue implements TextureUploadBatch.Recorder<String> {
        final List<String> calls = new ArrayList<>();
        boolean failSubmit;

        @Override
        public void begin() {
            calls.add("begin");
        }

        @Override
        public void submit(List<String> written) {
            calls.add("submit " + written);
            if (failSubmit) throw new IllegalStateException("submit failed");
        }
    }

    @Test
    void anOpenBatchRecordsEveryUploadOnceAndSubmitsOnce() {
        FakeQueue queue = new FakeQueue();
        TextureUploadBatch<String> batch = new TextureUploadBatch<>(queue);
        batch.open();
        batch.beforeUpload("normals");
        batch.beforeUpload("specular");
        batch.beforeUpload("normals");
        batch.close();
        assertEquals(List.of("begin", "submit [normals, specular]"), queue.calls);
    }

    @Test
    void aPumpWithoutUploadsAndUploadsOutsideAPumpRecordNothing() {
        FakeQueue queue = new FakeQueue();
        TextureUploadBatch<String> batch = new TextureUploadBatch<>(queue);
        batch.open();
        batch.close();
        batch.beforeUpload("flat fallback");
        batch.close();
        assertEquals(List.of(), queue.calls);
    }

    @Test
    void aFailedSubmitStillEndsTheBatch() {
        FakeQueue queue = new FakeQueue();
        TextureUploadBatch<String> batch = new TextureUploadBatch<>(queue);
        queue.failSubmit = true;
        batch.open();
        batch.beforeUpload("normals");
        assertThrows(IllegalStateException.class, batch::close);
        queue.failSubmit = false;
        batch.open();
        batch.beforeUpload("specular");
        batch.close();
        assertEquals(List.of("begin", "submit [normals]", "begin", "submit [specular]"), queue.calls);
    }
}
