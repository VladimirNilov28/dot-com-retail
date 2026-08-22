package ee.bytecore.backend;

import org.springframework.boot.SpringApplication;

import ee.bytecore.backend.config.TestcontainersConfiguration;

public class TestBackendApplication {

  public static void main(String[] args) {
    SpringApplication.from(BackendApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
