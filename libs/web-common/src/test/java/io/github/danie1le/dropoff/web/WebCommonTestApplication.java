package io.github.danie1le.dropoff.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot configuration {@code @WebMvcTest} looks for. web-common is a library with no application class of its
 * own, so its tests boot from this one, which scans this package.
 */
@SpringBootApplication
class WebCommonTestApplication {}
