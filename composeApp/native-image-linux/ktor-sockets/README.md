# ktor-sockets

`io.ktor.network.selector.InterestSuspensionsMap`, its four `*HandlerReference` fields.

Why: its static initializer builds an `AtomicReferenceFieldUpdater` for each of the four fields
by name, the first time any ktor raw socket is created (`TcpSocketBuilder.connect`: realtime /
Watch Party, the Supabase client's socket paths). Without the fields the class fails to
initialize and every later socket use fails. The JVM agent does not record it because the
reflective call is the JDK's own (`AtomicReferenceFieldUpdater.newUpdater`).

Found by the first Linux QA build (2026-10-17, `MissingReflectionRegistrationError ...
readHandlerReference`) and by the spike's first native run; all four are created in the same
loop, so all four are listed.
