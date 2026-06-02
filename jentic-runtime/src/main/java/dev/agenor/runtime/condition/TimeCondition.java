package dev.agenor.runtime.condition;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

import dev.agenor.core.condition.Condition;

/**
 * Pre-built conditions for time-based checks
 */
public class TimeCondition {

    /**
     * Check if current time is within business hours (9 AM - 5 PM)
     */
    public static Condition businessHours() {
        return agent -> {
            LocalTime now = LocalTime.now();
            return now.isAfter(LocalTime.of(9, 0)) &&
                   now.isBefore(LocalTime.of(17, 0));
        };
    }

    /**
     * Check if current day is a weekday (Monday-Friday)
     */
    public static Condition weekday() {
        return agent -> {
            DayOfWeek day = LocalDateTime.now().getDayOfWeek();
            return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
        };
    }

    /**
     * Check if current day is weekend
     */
    public static Condition weekend() {
        return agent -> {
            DayOfWeek day = LocalDateTime.now().getDayOfWeek();
            return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        };
    }

    /**
     * Check if current time is after specific hour
     */
    public static Condition afterHour(int hour) {
        return agent -> LocalTime.now().getHour() >= hour;
    }

    /**
     * Check if current time is before specific hour
     */
    public static Condition beforeHour(int hour) {
        return agent -> LocalTime.now().getHour() < hour;
    }

    /**
     * Check if current time is between two hours
     */
    public static Condition betweenHours(int startHour, int endHour) {
        return agent -> {
            int currentHour = LocalTime.now().getHour();
            return currentHour >= startHour && currentHour < endHour;
        };
    }
}
