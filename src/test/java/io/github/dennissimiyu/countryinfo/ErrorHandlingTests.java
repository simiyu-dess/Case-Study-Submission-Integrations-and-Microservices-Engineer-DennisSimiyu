package io.github.dennissimiyu.countryinfo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies that errors map to the right HTTP status instead of a generic 500. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorHandlingTests {

    @Autowired
    MockMvc mvc;

    @Test
    void unknownPathIs404() throws Exception {
        mvc.perform(get("/does-not-exist")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unknownIdIs404() throws Exception {
        mvc.perform(get("/api/v1/countries/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Country info with id 999999 not found"));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get("/api/v1/countries/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(patch("/api/v1/countries/1")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post("/api/v1/countries").contentType(MediaType.TEXT_PLAIN).content("kenya"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankNameIs400() throws Exception {
        mvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }
}
