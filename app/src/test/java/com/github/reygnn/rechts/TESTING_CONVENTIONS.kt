package com.github.reygnn.rechts

/**
 * ============================================================================
 * COROUTINE TEST DISPATCHER — PROJECT CONVENTION
 * ============================================================================
 *
 * TL;DR: Use ONE dispatcher everywhere. Never mix dispatcher instances.
 *
 * THE RULE:
 *
 *   @get:Rule
 *   val mainRule = MainDispatcherRule()   // sets Dispatchers.Main
 *
 *   @Test
 *   fun `my test`() = runTest(mainRule.dispatcher) {
 *       val vm = SomeViewModel(repo)
 *       vm.doSomething()
 *       advanceUntilIdle()
 *       // assert...
 *   }
 *
 * Why pass `mainRule.dispatcher` to `runTest`: plain `runTest { }` creates
 * its own `TestCoroutineScheduler`, so `advanceUntilIdle()` /
 * `advanceTimeBy()` will not drive code that runs on `Dispatchers.Main`.
 * Tests become flaky.
 *
 * `MainDispatcherRule` typically uses `UnconfinedTestDispatcher`, so many
 * tests don't even need an explicit `advanceUntilIdle()` — coroutines
 * launched from `viewModelScope.launch { ... }` in a ViewModel's `init`
 * block run synchronously inside the constructor.
 *
 * EVENT COLLECTORS THAT START LATE — Turbine
 *
 *   vm.state.test {
 *       assertThat(awaitItem().success).isFalse()
 *       vm.login()
 *       skipItems(1)            // loading state
 *       val final = awaitItem()
 *       assertThat(final.success).isTrue()
 *       cancelAndIgnoreRemainingEvents()
 *   }
 *
 * Use Turbine when a single test needs to observe a sequence of states
 * (login flow: idle → loading → success). For single-snapshot assertions,
 * `vm.state.value` directly is fine.
 *
 * ANTI-PATTERNS (will break tests silently):
 * ✗ private val testDispatcher = StandardTestDispatcher()  // second dispatcher!
 * ✗ private val testScope = TestScope(testDispatcher)      // separate scheduler!
 * ✗ @Before fun setUp() { Dispatchers.setMain(testDispatcher) }  // conflicts with rule!
 *
 * EXCEPTION — when a SECOND dispatcher backed by the SAME scheduler IS needed:
 *
 * For init-time events on `MutableSharedFlow` (no replay): the test must
 * subscribe BEFORE the emission. With `UnconfinedTestDispatcher`, init's
 * launch runs synchronously inside the constructor — the event fires
 * before turbine's `event.test {}` block exists, and `MutableSharedFlow`
 * without replay drops it. Pass `StandardTestDispatcher(testScheduler)`
 * as the ViewModel's `mainDispatcher` constructor parameter for that one
 * test:
 *
 *   val testDispatcher = StandardTestDispatcher(testScheduler)
 *   val vm = MyViewModel(useCase, mainDispatcher = testDispatcher)
 *   vm.event.test {
 *       advanceUntilIdle()       // run queued init launch
 *       val event = awaitItem()  // subscriber catches emitted event
 *       assertTrue(event is ShowError)
 *   }
 *
 * Not applicable when:
 *   - the event is emitted in response to a USER ACTION (subscriber is
 *     already attached by then).
 *   - the flow is a `StateFlow` or `SharedFlow` with `replay >= 1` (the
 *     late subscriber gets the cached value).
 * ============================================================================
 */

/**
 * ============================================================================
 * MOCKK CONVENTIONS
 * ============================================================================
 *
 * VARIABLE NAMING — NO `mock` PREFIX
 *
 * Mock variables are named after what they represent, not prefixed with
 * `mock`. The `mockk(...)` initializer or `@MockK` annotation already
 * declares the variable as a mock; a prefix is redundant noise.
 *
 *     // ✅ correct
 *     private val repo: SomeRepository = mockk(relaxed = true)
 *     every { repo.foo(any()) } returns "..."
 *
 *     // ❌ wrong
 *     private val mockRepo: SomeRepository = mockk(relaxed = true)
 *     every { mockRepo.foo(any()) } returns "..."
 *
 *
 * SUSPEND vs NON-SUSPEND — KNOW WHICH
 *
 * Pick `coEvery` / `coVerify` for any function declared `suspend`. Pick
 * `every` / `verify` for properties (incl. `val` of type `Flow`) and
 * regular non-suspend functions. Getting this wrong gives confusing
 * runtime errors, not compile errors.
 *
 *     // Flow as a property → every
 *     every { repo.session } returns MutableStateFlow(testSession)
 *
 *     // Suspend function returning Result → coEvery
 *     coEvery { repo.item(any()) } returns Result.success(item)
 *
 *
 * RELAXED vs RELAXUNITFUN
 *
 * Two different "relax" flags:
 *   - `relaxUnitFun = true` relaxes ONLY non-suspend `Unit`-returning fns.
 *   - `relaxed = true` relaxes EVERYTHING, including suspend functions.
 *
 * For repository-style mocks with suspend methods, ALWAYS use
 * `relaxed = true`. `relaxUnitFun = true` compiles but throws
 * `MockKException: no answer found` at the first suspend call.
 *
 *     // ✅ relaxed for repositories with suspend methods
 *     private val repo: SomeRepository = mockk(relaxed = true)
 *
 *
 * MOCKING SINGLETON OBJECTS — mockkObject
 *
 * To stub methods on a Kotlin `object`, `mockkObject(MyObject)` in
 * `@Before` and `unmockkObject(...)` in `@After`. After that,
 * `every { MyObject.someMethod(...) } returns ...` works.
 *
 *     @Before fun setUp() = mockkObject(MyObject)
 *     @After  fun tearDown() = unmockkObject(MyObject)
 *
 *
 * SHARED FLOW emit — emit(Unit) NOT emit(any())
 *
 * MockK does not match `Unit` reliably via `any()`. On a
 * `MutableSharedFlow<Unit>`:
 *
 *     // ❌ does not match
 *     coEvery { trigger.emit(any()) } just Runs
 *     coVerify { trigger.emit(any()) }
 *
 *     // ✅ matches
 *     coEvery { trigger.emit(Unit) } just Runs
 *     coVerify { trigger.emit(Unit) }
 *
 * For `MutableSharedFlow<T>` with non-Unit T, `any()` is fine as usual.
 *
 *
 * MOCKITO → MOCKK MIGRATION (cheat sheet for porting old tests)
 *
 *   Mockito                                          MockK
 *   ─────────────────────────────────────────────    ─────────────────────────────────────────
 *   @RunWith(MockitoJUnitRunner::class)              (drop entirely)
 *   @Mock private lateinit var x: T                  private val x: T = mockk(relaxed = true)
 *   MockitoAnnotations.openMocks(this) in @Before    (drop entirely)
 *   `when`(x.f()).thenReturn(v)                      every { x.f() } returns v
 *   `when`(x.suspendF()).thenReturn(v)               coEvery { x.suspendF() } returns v
 *   `when`(x.f()).thenThrow(E())                     every { x.f() } throws E()
 *   verify(x).f(args)                                verify { x.f(args) }
 *   verify(x).suspendF(args)                         coVerify { x.suspendF(args) }
 *   verify(x, never()).f()                           verify(exactly = 0) { x.f() }
 *   verify(x, times(2)).f()                          verify(exactly = 2) { x.f() }
 *   eq(value)                                        value     // no wrapper needed
 *   any() / any<T>()                                 any() / any<T>()    // io.mockk.any
 *   Mockito.mock(View::class.java)                   mockk<View>(relaxed = true)
 * ============================================================================
 */

/**
 * ============================================================================
 * MUTABLESHAREDFLOW — BUFFER OVERFLOW TRAP
 * ============================================================================
 *
 * Default `MutableSharedFlow()` has `extraBufferCapacity = 0` and no
 * subscriber. In production, a subscriber is permanently collecting; in
 * tests, often nobody collects. The first `emit` SUSPENDS waiting for a
 * subscriber that never comes. JUnit times out at 60 s.
 *
 * Even with `extraBufferCapacity = 1`, that's only safe for the FIRST
 * emission. The second `emit` re-fills the buffer and suspends.
 *
 * TWO FIXES, BY CONTEXT
 *
 * 1) The test does not observe the event, only needs it to not hang:
 *
 *        val trigger = MutableSharedFlow<Unit>(
 *            replay = 0,
 *            extraBufferCapacity = 1,
 *            onBufferOverflow = BufferOverflow.DROP_OLDEST
 *        )
 *
 * 2) The test wants to observe the event — use Turbine, which attaches a
 *    collector synchronously before the trigger fires:
 *
 *        repo.events.test {
 *            repo.doSomething()
 *            assertEquals(Unit, awaitItem())
 *            cancelAndIgnoreRemainingEvents()
 *        }
 * ============================================================================
 */

/**
 * ============================================================================
 * DATASTORE — NEVER MOCK DataStore<Preferences> DIRECTLY
 * ============================================================================
 *
 * Applies only if the project uses Jetpack DataStore. Skip this section
 * if persistence is SharedPreferences-only or in-memory.
 *
 * `DataStore.edit()` and `DataStore.updateData()` are extension functions.
 * MockK cannot stub extension functions reliably.
 *
 *     // ❌ does NOT work — edit() is an extension function
 *     val dataStore = mockk<DataStore<Preferences>>()
 *     coEvery { dataStore.edit(any()) } returns preferencesOf()
 *
 * Two strategies:
 *
 * 1. For the store class itself (e.g. `SessionStore` that wraps the
 *    DataStore): Robolectric test against the real DataStore.
 *    `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [36])`,
 *    call `store.clear()` in `@Before` for isolation.
 *
 * 2. For ANY higher-level test that depends on stored state: do NOT
 *    touch DataStore. Mock the store interface (or, more commonly, the
 *    repository that wraps it). The exposed `Flow<…>` property is the
 *    seam:
 *
 *        val sessionFlow = MutableStateFlow<Session?>(testSession)
 *        val repo = mockk<SomeRepository>(relaxed = true)
 *        every { repo.session } returns sessionFlow
 *
 * If a project grows multiple DataStore consumers, consider a
 * `FakeDataStore` helper instead of repeating the Robolectric setup.
 * ============================================================================
 */

/**
 * ============================================================================
 * ROBOLECTRIC — SDK 36, JDK 21
 * ============================================================================
 *
 * Use only when a test genuinely needs Android runtime: real DataStore,
 * `android.net.Uri.encode`, `android.graphics.*`, system services.
 * Robolectric tests are ~10x slower than JVM-only tests (framework boot
 * dominates the first test in a file — ~9s vs <1s for JVM).
 *
 * Annotate each Robolectric test class:
 *
 *     @RunWith(RobolectricTestRunner::class)
 *     @Config(sdk = [36])
 *     class XyzTest { ... }
 *
 * The SDK pin is required — Robolectric defaults to the manifest's
 * `targetSdk` and SDK 36 needs JDK 21 (see `~/.claude/CLAUDE.md`).
 *
 * APPLICATION OVERRIDE — only if needed
 *
 * If the project's `Application.onCreate()` does heavy / stateful work
 * (global handlers, background threads, file I/O), create
 * `app/src/test/resources/robolectric.properties` with:
 *
 *     application=android.app.Application
 *
 * This routes all Robolectric tests to a no-op Application by default.
 * Tests that DO need the production / Hilt-managed Application then
 * override per-class with `@Config(application = ...)`.
 *
 * For projects with a thin Application class (just instantiating
 * singletons), the manifest's Application is fine — skip the
 * properties file.
 * ============================================================================
 */

/**
 * ============================================================================
 * BEHAVIOURAL DISCIPLINE
 * ============================================================================
 *
 * 1. MockK only. No Mockito anywhere.
 * 2. Read the implementation alongside the test. The two are designed to
 *    be read together; subtle asymmetries are invisible to a test author
 *    who only reads one side.
 * 3. Honest KDoc beats fake tests. If a method is genuinely not testable
 *    on the JVM (Cast SDK against a real receiver, system-API path),
 *    document why in the production code and skip the test. A pseudo-test
 *    that asserts on mocked-out machinery proves nothing.
 * ============================================================================
 */
