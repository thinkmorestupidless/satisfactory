# What satisfactory would like from ankka

Found while building feature 001 against ankka 0.7.1. Each item says what we did instead, so it is
clear what would change if ankka did it. In the order they would help.

1. **Run-to-completion workloads (`AnkkaJob`) and machine credentials.** Unchanged from DESIGN.md §8
   and §11.1: the precondition for a dedicated pod per solve and for bring-your-own-model. Until then
   satisfactory runs a fixed pool of workers.

2. **A larger instance type.** `large` is 2 vCPU / 2 GiB, so a worker has one solve slot (one core
   stays free for cluster heartbeats), and a 2 GB uncompressed request cannot be held by an `api`
   instance at all (SC-012 is capped, not exercised). A 4–8 vCPU type would make the pool's capacity
   something other than its instance count.

3. **SSE frames as sent.** `sse` JSON-encodes each element into a `data:` line, emits no `id:` and no
   comment frames. satisfactory sends `{"heartbeat":true}` data frames for keep-alive, puts `seq` in
   the payload, resumes with `?after=`, and `satisfactory-client` decodes each frame twice. With raw
   `data:` lines, `id:` and `:` comments, a browser `EventSource` would work unaided and resume by
   `Last-Event-ID`.

4. **Request decoding and limits in one place.** Bodies are read with `toStrict`, capped by
   `pekko.http.parsing.max-to-strict-bytes` (8 MiB) as well as `max-content-length`, and
   `Content-Encoding` is not decoded. satisfactory sets both limits and inflates gzip itself. An
   `ankka.http.max-body` setting, and decoding by the server, would remove both.

5. **More than two path parameters, or a raw route.** The model API is one endpoint per model with
   the model in the prefix; configuration profiles moved the model from the path to the body; blob
   refs travel as an encoded second segment. None of it is wrong, all of it is shaped by the limit.

6. **Error bodies an application can choose.** ankka's own refusals (an ACL's 401/403, an unknown
   route) answer `{status, error}`; satisfactory's handlers answer Timefold's `ErrorInfo`. A hook to
   render refusals would let the whole API speak one error shape. The 401 challenge is always
   `Bearer …`, even for an API-key ACL.

7. **Serving `RuntimeExtension.routes`.** They are listed, not served, so `/metrics` is an ordinary
   endpoint behind a bearer token rather than a management-port route next to `/ankka/metrics`.

8. **A clock in `CommandContext`.** Entities cannot read the time, so every satisfactory command
   carries `at: Instant` from its caller. A runtime-provided, replay-safe timestamp would remove that
   parameter from about thirty commands.
