package kr.co.wooaeyoung.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogServiceTest {
    @Test void findsKoreanProductName() throws Exception {
        CatalogService catalog = new CatalogService(new ObjectMapper());
        assertEquals("supp-calcium", catalog.search("\uCE7C\uC298", null, 20).get(0).id());
    }
}
