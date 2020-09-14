package dev.andymacdonald.chaos.strategy;

public enum ChaosStrategy {
    NO_CHAOS,
    INTERNAL_SERVER_ERROR,
    BAD_REQUEST,
    DELAY_REQUEST,
    INSTANT_REQUEST_DELAY_RESPONSE,
    RANDOM_HAVOC
}
