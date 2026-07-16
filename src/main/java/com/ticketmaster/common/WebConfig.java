package com.ticketmaster.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

// Serialize Page responses via a stable DTO ({ content: [...], page: { size, number,
// totalElements, totalPages } }) instead of Jackson-serializing PageImpl as-is, whose JSON shape
// Spring Data does not guarantee across versions.
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class WebConfig {
}
