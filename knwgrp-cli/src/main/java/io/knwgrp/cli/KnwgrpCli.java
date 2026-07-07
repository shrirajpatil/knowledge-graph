package io.knwgrp.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "cartograph",
        mixinStandardHelpOptions = true,
        version = "Cartograph 0.1.0-SNAPSHOT",
        description = "Cartograph — an AI-free static knowledge graph generator for Java Spring Boot microservices.",
        subcommands = { AnalyzeCommand.class }
)
public class KnwgrpCli implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new KnwgrpCli()).execute(args);
        System.exit(exitCode);
    }
}
