package com.teacai.wiki;

import com.teacai.wiki.controller.RequestAdmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.event.HttpRequestTerminatedEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestAdmissionTest {
    @Test void boundsRequestsBeforeBindingAndReleasesExactlyOnceAfterTermination() {
        var metrics = new SimpleMeterRegistry();
        try {
            var admission = new RequestAdmission(metrics);
            var first = HttpRequest.POST("/one", "large-body");
            var second = HttpRequest.GET("/two");
            assertNull(admission.admit(first));
            assertNull(admission.admit(second));
            assertEquals(503, admission.admit(HttpRequest.GET("/three")).code());
            assertNull(admission.admit(HttpRequest.GET("/_assets/style.css")));
            var terminated = new HttpRequestTerminatedEvent(first);
            admission.onApplicationEvent(terminated);
            admission.onApplicationEvent(terminated);
            assertNull(admission.admit(HttpRequest.GET("/four")));
            assertEquals(503, admission.admit(HttpRequest.GET("/five")).code());
            assertEquals(2.0, metrics.get("wiki.work.active").gauge().value());
        } finally { metrics.close(); }
    }
}
