package com.test.engine.controller;

import com.test.engine.model.CardPack;
import com.test.engine.model.CardPackLoader;
import com.test.engine.model.PuppetTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public catalog of card packs and the training dummy template.
 */
@RestController
@RequestMapping("/api/packs")
public class PackController {

    private final CardPackLoader cardPackLoader;

    public PackController(CardPackLoader cardPackLoader) {
        this.cardPackLoader = cardPackLoader;
    }

    @GetMapping
    public List<CardPack> list() {
        return cardPackLoader.all();
    }

    @GetMapping("/puppet")
    public PuppetTemplate puppet() {
        return cardPackLoader.getPuppet("training-dummy");
    }
}
