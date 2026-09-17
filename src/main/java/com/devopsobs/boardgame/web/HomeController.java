package com.devopsobs.boardgame.web;

import com.devopsobs.boardgame.service.BoardGameService;
import org.slf4j.MDC;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    private final BoardGameService service;

    public HomeController(BoardGameService service) { this.service = service; }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("games", service.list(null));
        model.addAttribute("requestId", MDC.get("http.request.id"));
        return "index";
    }
}
