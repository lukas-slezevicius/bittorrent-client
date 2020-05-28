package com.slezevicius.sembucha;

public class Pair<L, R> {
    private final L left;
    private final R right;

    public Pair(L left, R right) {
        this.left = left;
        this.right = right;
    }

    public L getLeft() {
        return left;
    }

    public R getRight() {
        return right;
    }

    @Override
    public int hashCode() {
        return left.hashCode() ^ right.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Pair)) {
            return false;
        }
        Pair pairObj = (Pair) obj;
        return this.left.equals(pairObj.getLeft()) && this.right.equals(pairObj.getRight());
    }

    @Override
    public String toString() {
        return String.format("<%s, %s>", this.left.toString(), this.right.toString());
    }
}