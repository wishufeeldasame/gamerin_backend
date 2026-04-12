package com.gamerin.backend.global.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootRedirectController {

    private final String frontendBaseUrl;

    public RootRedirectController(@Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @GetMapping("/")
    public String redirectRoot() {
        return "redirect:" + frontendBaseUrl + "/home";
    }
}
