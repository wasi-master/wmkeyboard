"""asyncio.base_futures: isfuture, as asyncio has it."""


def isfuture(obj):
    from asyncio import isfuture as _isfuture
    return _isfuture(obj)
