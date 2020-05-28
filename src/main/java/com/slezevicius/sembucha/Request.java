package com.slezevicius.sembucha;

public class Request {
    public volatile int index;
    public volatile int begin;
    public volatile byte[] block;

    Request(int index, int begin, byte[] block) {
        this.index = index;
        this.begin = begin;
        this.block = block;
    }

    Request(int index, int begin, int length) {
        this.index = index;
        this.begin = begin;
        this.block = new byte[length];
    }

}