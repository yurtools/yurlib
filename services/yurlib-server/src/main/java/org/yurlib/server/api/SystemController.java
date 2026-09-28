package org.yurlib.server.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping
    public Map<String, String> status() {
        return Map.of("name", "Yurlib", "status", "ready");
    }
}
