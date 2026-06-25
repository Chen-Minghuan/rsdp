package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @PostMapping("/entry")
    public Result<Map<String, Object>> entry(@RequestParam("image") MultipartFile image) throws IOException {
        Map<String, Object> result = productService.createEntry(image);
        return Result.ok(result);
    }
}
