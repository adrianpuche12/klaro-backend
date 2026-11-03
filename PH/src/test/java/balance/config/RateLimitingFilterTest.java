package balance.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    private final RateLimitingFilter filter = new RateLimitingFilter();

    private int call(String remoteAddr, String fakeForwardedFor) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v2/stores");
        req.setRemoteAddr(remoteAddr);
        if (fakeForwardedFor != null) req.addHeader("X-Forwarded-For", fakeForwardedFor);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void fakeXForwardedFor_doesNotBypassTheLimit() throws Exception {
        for (int i = 0; i < RateLimitingFilter.CAPACITY; i++) {
            assertThat(call("203.0.113.7", "10.0.0." + i)).isEqualTo(200);
        }
        assertThat(call("203.0.113.7", "198.51.100.99")).isEqualTo(429);
    }

    @Test
    void otherClientsAreNotAffected() throws Exception {
        for (int i = 0; i < RateLimitingFilter.CAPACITY; i++) call("203.0.113.7", null);
        assertThat(call("203.0.113.7", null)).isEqualTo(429);
        assertThat(call("203.0.113.8", null)).isEqualTo(200);
    }

    @Test
    void healthIsNeverLimited() throws Exception {
        for (int i = 0; i < RateLimitingFilter.CAPACITY + 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
            req.setRemoteAddr("203.0.113.9");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void trackedIpsAreBounded() throws Exception {
        long extra = 2_000;
        for (long i = 0; i < RateLimitingFilter.MAX_TRACKED_IPS + extra; i++) {
            call("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255), null);
        }
        assertThat(filter.trackedIps()).isLessThanOrEqualTo(RateLimitingFilter.MAX_TRACKED_IPS);
    }
}
