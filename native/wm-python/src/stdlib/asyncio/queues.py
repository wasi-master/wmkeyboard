"""asyncio.queues: queues. They live in the asyncio module;
this is where CPython keeps them."""

from asyncio import (
    Queue,
    PriorityQueue,
    LifoQueue,
    QueueFull,
    QueueEmpty,
    QueueShutDown,
)

__all__ = ("Queue", "PriorityQueue", "LifoQueue", "QueueFull", "QueueEmpty", "QueueShutDown")
