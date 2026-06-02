# OneShotBehavior - Single Execution

## Overview

`OneShotBehavior` executes its action **once** and then automatically stops. After execution, `isActive()` returns `false` and the behavior is removed from the scheduler.

**Since**: v0.1.0 | **Type**: `BehaviorType.ONE_SHOT` | **Package**: `dev.agenor.runtime.behavior`

---

## Usage

### Subclass

```java
public class SendWelcomeMessageBehavior extends OneShotBehavior {

    public SendWelcomeMessageBehavior() {
        super("send-welcome");
    }

    @Override
    protected void action() {
        messageService.send(Message.builder()
            .topic("agent.started")
            .content(getAgent().getAgentId())
            .build());
    }
}
agent.addBehavior(new SendWelcomeMessageBehavior());
```

### Factory

```java
agent.addBehavior(OneShotBehavior.from("init", () -> {
    log.info("Agent initializing");
    loadConfiguration();
}));
```

---

## Constructors

```java
protected OneShotBehavior()
protected OneShotBehavior(String behaviorId)
```

## Factory Methods

```java
static OneShotBehavior from(Runnable action)
static OneShotBehavior from(String name, Runnable action)
```

---

## See Also

- [WakerBehavior](WakerBehavior.md) - Delayed / condition-triggered one-shot
- [CyclicBehavior](CyclicBehavior.md) - Repeated execution
- [Behavior Overview](README.md)
