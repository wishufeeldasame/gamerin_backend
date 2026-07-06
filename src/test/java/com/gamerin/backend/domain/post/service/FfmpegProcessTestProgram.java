package com.gamerin.backend.domain.post.service;

final class FfmpegProcessTestProgram {

    private FfmpegProcessTestProgram() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "success" : args[0];
        switch (mode) {
            case "sleep" -> Thread.sleep(10_000L);
            case "flood" -> System.out.print("x".repeat(250_000));
            case "flood-sleep" -> {
                System.out.print("x".repeat(250_000));
                System.out.flush();
                Thread.sleep(10_000L);
            }
            case "fail" -> {
                System.err.print("internal ffmpeg diagnostic secret");
                System.exit(7);
            }
            default -> System.out.print("ffmpeg-ok");
        }
    }
}
