"""
The account's event loop, run on a thread of its own instead of being borrowed by each caller.

FindMy.py's synchronous `AppleAccount` drives its async account by calling
`self._evt_loop.run_until_complete(...)` from whichever thread asked. A loop can only be run by
one thread at a time, so Java serialised every call into Python behind `PythonLock` - and a
first import, which walks thousands of key indices inside a *single* `fetch_location`, held it
for minutes. The iCloud screen waited for the whole walk before it could say anything to Apple
(issue #126).

:func:`attach` swaps the account's `_evt_loop` for an :class:`AccountLoop`, whose
`run_until_complete` hands the coroutine to the real loop - now running forever on its own
thread - and waits for the answer. **Same loop, same aiohttp session, same identity**: rule 11 is
untouched, because only *who drives* the loop changes, not what is on it.

**The account is still used by one thing at a time, and that is deliberate.** FindMy.py's async
account is not safe to use concurrently, and not only in theory: `request_pet`, which opening a
keychain session calls, swaps the account's login state out for the length of a GSA round trip
and puts it back in a `finally`. Anything reading `dsid` or `service_tokens` in that window gets
a `KeyError` or an `InvalidStateError`. A 401 inside `fetch_raw_reports` re-authenticates the
same way. So every operation takes a turn on the account, and what changed is the size of a
turn:

- an ordinary call takes one turn for its whole length - the exclusion it always had, measured
  in coroutines rather than Java calls
- a location fetch inside :func:`interleaving` takes a turn **per request to Apple** instead,
  by way of a wrapped `fetch_raw_reports`, and nothing between requests touches the account

So a call that arrives during a long walk waits for one request, not for the walk. Between
requests the fetch is only deriving keys and decrypting, which needs nothing the account holds.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import contextvars
import threading
import traceback
import weakref
from contextlib import contextmanager
from typing import Any, Awaitable, Callable, Coroutine, Iterator, TypeVar

_T = TypeVar("_T")

# How long closing waits for cancelled calls to unwind, and then for the thread to stop. Both
# are about a coroutine that will not let go, which should never happen; the bound is there so
# that it cannot hang whoever is closing the account.
_SHUTDOWN_TIMEOUT_SECONDS = 10.0

# Set on the calling thread by `interleaving`, read by `run_until_complete` on the same thread.
_interleaving = threading.local()

# Whether the running task already holds the account's turn. A context variable rather than a
# task identity, so that it is inherited by anything the holder spawns - which is part of the same
# operation, and would otherwise deadlock waiting for a turn its own parent is holding.
_holdingTurn: contextvars.ContextVar[bool] = contextvars.ContextVar(
    "holdingTurn", default=False)


@contextmanager
def interleaving() -> Iterator[None]:
    """
    Let the account calls made on this thread take turns per request rather than per call.

    For location fetches, and only for them: they are the long calls, and the only ones whose
    requests are known to be separable - `fetch_raw_reports` is the one place they touch the
    account. Anything else made inside this block would run without any exclusion at all.
    """
    previous = getattr(_interleaving, "on", False)
    _interleaving.on = True
    try:
        yield
    finally:
        _interleaving.on = previous


class AccountLoop:
    """
    Stands in for an account's `_evt_loop`, which FindMy.py uses for nothing but
    `run_until_complete`.

    Deliberately not a general proxy for the loop. Anything that reached past these methods for
    `run_forever` or `stop` would be fighting the thread that owns it, so they are not offered.
    """

    def __init__(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop
        self._turn = asyncio.Lock()
        # Guards `_closed` against a submission racing a close: anything submitted before the
        # flag flips is on the loop by the time the close cancels what is there.
        self._state = threading.Lock()
        self._closed = False
        # Whether report requests take turns of their own. Until they do, a fetch inside
        # `interleaving` must take one turn for its whole length like anything else, or it would
        # run with no exclusion at all.
        self._turnsPerRequest = False
        self._thread = threading.Thread(
            target=self._runForever, name="findmy-account-loop", daemon=True)
        self._thread.start()

    def _runForever(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    @property
    def loop(self) -> asyncio.AbstractEventLoop:
        return self._loop

    def is_running(self) -> bool:
        return not self._closed and self._loop.is_running()

    def is_closed(self) -> bool:
        return self._closed or self._loop.is_closed()

    def run_until_complete(self, coro: Coroutine[Any, Any, _T]) -> _T:
        """What FindMy.py's sync wrapper calls: run this on the account's loop, in its turn."""
        try:
            if self._turnsPerRequest and getattr(_interleaving, "on", False):
                return self._submit(coro)

            return self._submit(self.inTurn(lambda: coro))
        finally:
            # A no-op once it has run. For one refused, or cancelled while still queued for the
            # turn, it is what stops "coroutine was never awaited" landing in the log as if it
            # were a bug.
            coro.close()

    async def inTurn(self, makeCall: Callable[[], Awaitable[_T]]) -> _T:
        """
        Make and await a call while holding the account's turn, or straight through if this
        task already holds it.

        **A factory, so the call is made inside the turn.** FindMy.py checks the login state
        when an account method is *called*, not when it is awaited - and `request_pet` holding
        the turn is exactly when that state is temporarily wrong.

        **Reentrant on purpose.** An ordinary call that reaches `fetch_raw_reports` - a sync
        `fetch_location` made outside `interleaving` - holds the turn already, and an
        `asyncio.Lock` taken twice by one holder waits on itself forever.
        """
        if _holdingTurn.get():
            return await makeCall()

        async with self._turn:
            holding = _holdingTurn.set(True)
            try:
                return await makeCall()
            finally:
                _holdingTurn.reset(holding)

    def _submit(self, coro: Coroutine[Any, Any, _T]) -> _T:
        if threading.current_thread() is self._thread:
            # Blocking the loop's own thread on something only that thread can run never ends.
            # An exception is a far better way to meet this than a frozen app.
            coro.close()
            raise RuntimeError(
                "A synchronous account call was made from the account's own event loop thread")

        with self._state:
            if self._closed:
                coro.close()
                raise RuntimeError("This account has been closed")

            future = asyncio.run_coroutine_threadsafe(coro, self._loop)

        try:
            return future.result()
        except concurrent.futures.CancelledError:
            # Only `close` cancels anything here, so this is the account going away under a call
            # that was waiting for it - a reason worth more than a bare CancelledError.
            raise RuntimeError(
                "The account was closed while this call was waiting for it") from None

    def stopOnceIdle(self) -> None:
        """
        Stop the thread once whatever is already on the loop has finished, without waiting here.

        For an account dropped without being closed - a failed sign-in, a code screen somebody
        walked away from - which used to cost two sockets until the collector ran, and would now
        also cost a thread idling forever. Called from a finaliser, on whatever thread the
        collector happens to be on, so it must not block.

        **Finished, not cancelled.** FindMy.py's own finaliser has just scheduled the session's
        `close` on this loop, and cancelling it would leak the very sockets it is closing.
        """
        with self._state:
            if self._closed:
                return
            self._closed = True

        try:
            self._loop.call_soon_threadsafe(self._loop.create_task, _finishThenStop())
        except RuntimeError:
            # The loop is closed already, so there is no thread left to stop.
            pass

    def shutDown(self) -> bool:
        """
        Cancel whatever is still waiting, stop the loop, and stop its thread.

        :return: whether the thread stopped, which is what makes the loop safe to hand back.
        """
        with self._state:
            if self._closed:
                return not self._thread.is_alive()
            self._closed = True

        try:
            asyncio.run_coroutine_threadsafe(
                _cancelEverythingElse(), self._loop).result(_SHUTDOWN_TIMEOUT_SECONDS)
        except Exception:
            print(f"Account loop: calls still waiting did not unwind:\n{traceback.format_exc()}")

        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(_SHUTDOWN_TIMEOUT_SECONDS)

        if self._thread.is_alive():
            print("Account loop: its thread did not stop; leaving the loop where it is.")
            return False

        return True


async def _cancelEverythingElse() -> None:
    current = asyncio.current_task()
    others = [task for task in asyncio.all_tasks() if task is not current]

    for task in others:
        task.cancel()

    await asyncio.gather(*others, return_exceptions=True)


async def _finishThenStop() -> None:
    current = asyncio.current_task()
    others = [task for task in asyncio.all_tasks() if task is not current]

    await asyncio.gather(*others, return_exceptions=True)
    asyncio.get_running_loop().stop()


def attach(account: Any) -> bool:
    """
    Run this account's loop on a thread of its own, from now until :func:`detach`.

    Call it once, straight after the account is built and before anything else uses it: the
    loop has to be idle to be handed to another thread.

    :return: whether it is attached. False leaves the account as FindMy.py built it, driven by
        whoever calls it - and :func:`icloud_bridge.openSession` refuses such an account, because
        the iCloud flow is only safe beside a location fetch when this is in place.
    """
    current = getattr(account, "_evt_loop", None)
    asyncAccount = getattr(account, "_asyncacc", None)

    if isinstance(current, AccountLoop):
        return True

    if current is None or asyncAccount is None:
        print(
            "FindMy.py's account internals have changed: no _evt_loop or _asyncacc, so the"
            " account's loop cannot be given a thread of its own.")
        return False

    if current.is_running() or current.is_closed():
        print("Not attaching the account loop: it is already running somewhere, or closed.")
        return False

    attached = AccountLoop(current)
    _takeTurnsPerRequest(asyncAccount, attached)
    account._evt_loop = attached

    try:
        # Holds the loop, never the account, or the account could never be collected to fire it.
        weakref.finalize(account, attached.stopOnceIdle)
    except TypeError:
        # Only an object that cannot be weakly referenced, which FindMy.py's account can be.
        # The thread then lives until `detach`, which is a leak and not a fault.
        print("Account loop: this account cannot be watched for collection; close it explicitly.")

    return True


def _takeTurnsPerRequest(asyncAccount: Any, attached: AccountLoop) -> None:
    """
    Make each report request take the account's turn on its own.

    `fetch_raw_reports` is the one place a location fetch touches the account: it reads the
    service token, asks Anisette for headers, and on a 401 re-authenticates. Every walk through
    key indices in FindMy.py's report fetcher comes through here, one request at a time, so this
    is the boundary where something else can be let in.

    Set on the instance, which is where the fetcher looks it up (`self._account.fetch_raw_reports`).

    **Missing is survivable, so it is not an error.** Attaching still gives the loop its thread,
    and fetches then take one turn each, exactly as they did before this module. Raising instead
    would fail the restore, and a failed restore signs the user out.
    """
    original = getattr(asyncAccount, "fetch_raw_reports", None)

    if original is None:
        print(
            "FindMy.py's account has no fetch_raw_reports, so location fetches cannot give way"
            " between requests; each will hold the account until it finishes.")
        return

    async def fetch_raw_reports(*args: Any, **kwargs: Any) -> Any:
        return await attached.inTurn(lambda: original(*args, **kwargs))

    asyncAccount.fetch_raw_reports = fetch_raw_reports
    attached._turnsPerRequest = True


def detach(account: Any) -> None:
    """
    Stop the account's loop thread and hand the loop back, once the account is closed.

    Handed back rather than left behind so a closed account behaves exactly as it did before
    this module existed - including `Closable.__del__`, which closes on whatever loop it finds.
    """
    attached = getattr(account, "_evt_loop", None)

    if not isinstance(attached, AccountLoop):
        return

    if attached.shutDown():
        account._evt_loop = attached.loop
