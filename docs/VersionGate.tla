----------------------------- MODULE VersionGate -----------------------------
(***************************************************************************
This module is the formal, per-resource counterpart of
docs/business-rules.md. Different resources are independent compositions of
this state machine.

It specifies coordination state, not external live-data contents. Successful
write completion, clean rollback, recovery verification, snapshot derivation,
and enforcement of fencing by protected components are environment assertions.
Session identity is modeled as an unforgeable bearer capability; presenting the
currently open identity is what "owning session" means in this model.

SessionIds, OperationIds, Payloads, and TimeValues are finite TLC model bounds.
Exhausting one of those sets is not a business outcome. Versions remain the
unbounded positive integers and are allocated deterministically.
***************************************************************************)

EXTENDS Naturals, FiniteSets, TLC

CONSTANTS SessionIds, OperationIds, Payloads, TimeValues,
          NoSession, NoRecovery, NoResolution, NoPayload, NoTerminalOutcome

\* Zero is not a coordinator version and is never a valid open-session
\* deadline, so it is the homogeneous TLC representation of both NONE values.
NoVersion  == 0
NoDeadline == 0
Sentinels  ==
  {NoSession, NoRecovery, NoResolution, NoPayload, NoTerminalOutcome}

ASSUME /\ SessionIds # {}
       /\ OperationIds # {}
       /\ Payloads # {}
       /\ TimeValues # {}
       /\ IsFiniteSet(SessionIds)
       /\ IsFiniteSet(OperationIds)
       /\ IsFiniteSet(Payloads)
       /\ IsFiniteSet(TimeValues)
       /\ TimeValues \subseteq Nat
       /\ 0 \in TimeValues
       /\ SessionIds \cap OperationIds = {}
       /\ SessionIds \cap Payloads = {}
       /\ OperationIds \cap Payloads = {}
       /\ Cardinality(Sentinels) = 5
       /\ Sentinels \cap
            (SessionIds \cup OperationIds \cup Payloads \cup TimeValues) = {}
       /\ \E deadline \in TimeValues : deadline > 0

SnapshotEnabled  == "ENABLED"
SnapshotDisabled == "DISABLED"
AllowGap         == "ALLOW_GAP"
RequireCurrent   == "REQUIRE_CURRENT_SNAPSHOT"
BlockWriter      == "BLOCK_WRITER"
InvalidateSnap   == "INVALIDATE_SNAPSHOT"
NotApplicable    == "NOT_APPLICABLE"

ValidPolicies ==
  { [snapshotSupport      |-> SnapshotDisabled,
     missingCurrent       |-> AllowGap,
     writerDuringSnapshot |-> NotApplicable],
    [snapshotSupport      |-> SnapshotEnabled,
     missingCurrent       |-> AllowGap,
     writerDuringSnapshot |-> BlockWriter],
    [snapshotSupport      |-> SnapshotEnabled,
     missingCurrent       |-> AllowGap,
     writerDuringSnapshot |-> InvalidateSnap],
    [snapshotSupport      |-> SnapshotEnabled,
     missingCurrent       |-> RequireCurrent,
     writerDuringSnapshot |-> BlockWriter] }

WriteKind    == "WRITE"
LiveReadKind == "LIVE_READ"
SnapshotKind == "SNAPSHOT"
RecoveryKind == "RECOVERY"
SessionKinds == {WriteKind, LiveReadKind, SnapshotKind, RecoveryKind}

RestorePrevious    == "RESTORE_PREVIOUS"
AcceptWriteVersion == "ACCEPT_WRITE_VERSION"
RecoveryResolutions == {RestorePrevious, AcceptWriteVersion}

ByVersion       == "BY_VERSION"
Current         == "CURRENT"
LatestAvailable == "LATEST_AVAILABLE"
SnapshotSelectors == {ByVersion, Current, LatestAvailable}

AllowWhileWriting == "ALLOW_WHILE_WRITING"
RejectIfWriting   == "REJECT_IF_WRITING"
DuringWriteChoices == {AllowWhileWriting, RejectIfWriting}

Admit   == "ADMIT"
Publish == "PUBLISH"

HistoryOutcomes ==
  { "WRITE_COMPLETED", "WRITE_ABORTED_CLEAN", "WRITE_UNCERTAIN",
    "WRITE_EXPIRED", "LIVE_READ_COMPLETED", "LIVE_READ_EXPIRED",
    "SNAPSHOT_COMPLETED", "SNAPSHOT_ABORTED", "SNAPSHOT_EXPIRED",
    "SNAPSHOT_INVALIDATED", "RECOVERY_RESTORED", "RECOVERY_ACCEPTED",
    "RECOVERY_ABORTED", "RECOVERY_EXPIRED" }

WriteHistoryOutcomes ==
  {"WRITE_COMPLETED", "WRITE_ABORTED_CLEAN", "WRITE_UNCERTAIN",
   "WRITE_EXPIRED"}
LiveReadHistoryOutcomes == {"LIVE_READ_COMPLETED", "LIVE_READ_EXPIRED"}
SnapshotHistoryOutcomes ==
  {"SNAPSHOT_COMPLETED", "SNAPSHOT_ABORTED", "SNAPSHOT_EXPIRED",
   "SNAPSHOT_INVALIDATED"}
RecoveryHistoryOutcomes ==
  {"RECOVERY_RESTORED", "RECOVERY_ACCEPTED", "RECOVERY_ABORTED",
   "RECOVERY_EXPIRED"}

HistoryOutcomeMatches(kind, outcome) ==
  IF kind = WriteKind THEN outcome \in WriteHistoryOutcomes
  ELSE IF kind = LiveReadKind THEN outcome \in LiveReadHistoryOutcomes
  ELSE IF kind = SnapshotKind THEN outcome \in SnapshotHistoryOutcomes
  ELSE outcome \in RecoveryHistoryOutcomes

OutcomeCodes == HistoryOutcomes \cup
  { "WRITE_ADMITTED", "LIVE_READ_ADMITTED", "SNAPSHOT_ADMITTED",
    "RECOVERY_ADMITTED", "SESSION_RENEWED", "RECOVERY_REQUIRED",
    "RECOVERY_NOT_REQUIRED", "RECOVERY_ACTIVE", "WRITE_ACTIVE",
    "LIVE_READ_ACTIVE", "EXPECTED_BASE_MISMATCH",
    "CURRENT_SNAPSHOT_REQUIRED", "SNAPSHOT_ACTIVE",
    "NO_ACTIVE_VERSION", "SNAPSHOT_SUPPORT_DISABLED",
    "SNAPSHOT_ALREADY_EXISTS", "SNAPSHOT_PAYLOAD_CONFLICT",
    "SNAPSHOT_NOT_FOUND", "CURRENT_SNAPSHOT_UNAVAILABLE",
    "WRITE_IN_PROGRESS", "SESSION_STALE", "SESSION_EXPIRED",
    "SESSION_ALREADY_TERMINAL", "DEADLINE_NOT_EXTENDED",
    "OPERATION_ID_REUSED" }

OperationKinds ==
  { "BEGIN_WRITE", "COMPLETE_WRITE", "ABORT_WRITE_CLEAN",
    "REPORT_WRITE_UNCERTAIN", "BEGIN_LIVE_READ", "COMPLETE_LIVE_READ",
    "BEGIN_SNAPSHOT", "SUBMIT_SNAPSHOT", "ABORT_SNAPSHOT",
    "RENEW_SESSION", "BEGIN_RECOVERY", "RESOLVE_RECOVERY",
    "ABORT_RECOVERY" }

VersionValues == Nat \ {0}
VersionOrNone == VersionValues \cup {NoVersion}
DeadlineOrNone == TimeValues \cup {NoDeadline}
ResolutionOrNone == RecoveryResolutions \cup {NoResolution}
PayloadOrNone == Payloads \cup {NoPayload}
SessionOrNone == SessionIds \cup {NoSession}

Request(kind, session, expectedBase, deadline, resolution, payload) ==
  [ kind         |-> kind,
    session      |-> session,
    expectedBase |-> expectedBase,
    deadline     |-> deadline,
    resolution   |-> resolution,
    payload      |-> payload ]

Result(code, session, version, fence, deadline, payload) ==
  [ code     |-> code,
    session  |-> session,
    version  |-> version,
    fence    |-> fence,
    deadline |-> deadline,
    payload  |-> payload,
    terminalOutcome |-> NoTerminalOutcome ]

TerminalResult(code, session, version, fence, deadline, payload,
               terminalOutcome) ==
  [ code     |-> code,
    session  |-> session,
    version  |-> version,
    fence    |-> fence,
    deadline |-> deadline,
    payload  |-> payload,
    terminalOutcome |-> terminalOutcome ]

RequestType ==
  [ kind         : OperationKinds,
    session      : SessionOrNone,
    expectedBase : VersionOrNone,
    deadline     : DeadlineOrNone,
    resolution   : ResolutionOrNone,
    payload      : PayloadOrNone ]

ResultType ==
  [ code     : OutcomeCodes,
    session  : SessionOrNone,
    version  : VersionOrNone,
    fence    : Nat,
    deadline : DeadlineOrNone,
    payload  : PayloadOrNone,
    terminalOutcome : HistoryOutcomes \cup {NoTerminalOutcome} ]

HistoryRecord(kind, outcome, version, baseVersion, deadline, fence, payload) ==
  [ kind        |-> kind,
    outcome     |-> outcome,
    version     |-> version,
    baseVersion |-> baseVersion,
    deadline    |-> deadline,
    fence       |-> fence,
    payload     |-> payload ]

HistoryRecordType ==
  [ kind        : SessionKinds,
    outcome     : HistoryOutcomes,
    version     : VersionOrNone,
    baseVersion : VersionOrNone,
    deadline    : TimeValues,
    fence       : Nat,
    payload     : PayloadOrNone ]

OperationEntryType == [request : RequestType, result : ResultType]

WriteSessionType ==
  [ id               : SessionIds,
    baseVersion      : VersionOrNone,
    candidateVersion : VersionValues,
    deadline         : TimeValues,
    fence            : VersionValues ]

LiveReadSessionType ==
  [ id           : SessionIds,
    boundVersion : VersionValues,
    deadline     : TimeValues ]

SnapshotSessionType ==
  [ id           : SessionIds,
    boundVersion : VersionValues,
    deadline     : TimeValues ]

RecoveryType ==
  [ uncertainVersion : VersionValues,
    previousVersion  : VersionOrNone ]

RecoverySessionType ==
  [ id       : SessionIds,
    deadline : TimeValues,
    fence    : VersionValues ]

Put(function, key, value) ==
  [x \in DOMAIN function \cup {key} |->
     IF x = key THEN value ELSE function[x]]

Remove(function, key) ==
  [x \in DOMAIN function \ {key} |-> function[x]]

RemoveAll(function, keys) ==
  [x \in DOMAIN function \ keys |-> function[x]]

Override(left, right) ==
  [x \in DOMAIN left \cup DOMAIN right |->
     IF x \in DOMAIN right THEN right[x] ELSE left[x]]

EmptyFunction == [x \in {} |-> x]

VARIABLES
  policy,
  activeVersion,
  lastAllocatedVersion,
  lastFence,
  time,
  writeSession,
  liveReadSessions,
  snapshotSession,
  snapshots,
  recovery,
  recoverySession,
  sessionHistory,
  operationResults,
  activatedVersions

CoreVars ==
  << policy, activeVersion, lastAllocatedVersion, lastFence, time,
     writeSession, liveReadSessions, snapshotSession, snapshots, recovery,
     recoverySession, sessionHistory, activatedVersions >>

vars ==
  << policy, activeVersion, lastAllocatedVersion, lastFence, time,
     writeSession, liveReadSessions, snapshotSession, snapshots, recovery,
     recoverySession, sessionHistory, operationResults, activatedVersions >>

WriteIds ==
  IF writeSession = NoSession THEN {} ELSE {writeSession.id}

SnapshotIds ==
  IF snapshotSession = NoSession THEN {} ELSE {snapshotSession.id}

RecoveryIds ==
  IF recoverySession = NoSession THEN {} ELSE {recoverySession.id}

OpenSessionIds ==
  WriteIds \cup DOMAIN liveReadSessions \cup SnapshotIds \cup RecoveryIds

FreshSession(id) ==
  /\ id \in SessionIds
  /\ id \notin OpenSessionIds
  /\ id \notin DOMAIN sessionHistory

OpenKindOf(id) ==
  IF id \in WriteIds THEN WriteKind
  ELSE IF id \in DOMAIN liveReadSessions THEN LiveReadKind
  ELSE IF id \in SnapshotIds THEN SnapshotKind
  ELSE RecoveryKind

OpenDeadlineOf(id) ==
  IF id \in WriteIds THEN writeSession.deadline
  ELSE IF id \in DOMAIN liveReadSessions THEN liveReadSessions[id].deadline
  ELSE IF id \in SnapshotIds THEN snapshotSession.deadline
  ELSE recoverySession.deadline

OpenVersionOf(id) ==
  IF id \in WriteIds THEN writeSession.candidateVersion
  ELSE IF id \in DOMAIN liveReadSessions THEN liveReadSessions[id].boundVersion
  ELSE IF id \in SnapshotIds THEN snapshotSession.boundVersion
  ELSE recovery.uncertainVersion

OpenBaseOf(id) ==
  IF id \in WriteIds THEN writeSession.baseVersion
  ELSE IF id \in RecoveryIds THEN recovery.previousVersion
  ELSE NoVersion

OpenFenceOf(id) ==
  IF id \in WriteIds THEN writeSession.fence
  ELSE IF id \in RecoveryIds THEN recoverySession.fence
  ELSE 0

KnownVersionOf(id) ==
  IF id \in OpenSessionIds THEN OpenVersionOf(id)
  ELSE IF id \in DOMAIN sessionHistory THEN sessionHistory[id].version
  ELSE NoVersion

KnownFenceOf(id) ==
  IF id \in OpenSessionIds THEN OpenFenceOf(id)
  ELSE IF id \in DOMAIN sessionHistory THEN sessionHistory[id].fence
  ELSE 0

KnownDeadlineOf(id) ==
  IF id \in OpenSessionIds THEN OpenDeadlineOf(id)
  ELSE IF id \in DOMAIN sessionHistory THEN sessionHistory[id].deadline
  ELSE NoDeadline

KnownPayloadOf(id) ==
  IF id \in DOMAIN sessionHistory THEN sessionHistory[id].payload ELSE NoPayload

SessionFailureCode(id) ==
  IF id \in DOMAIN sessionHistory
  THEN IF sessionHistory[id].outcome \in
          {"WRITE_EXPIRED", "LIVE_READ_EXPIRED", "SNAPSHOT_EXPIRED",
           "RECOVERY_EXPIRED"}
       THEN "SESSION_EXPIRED"
       ELSE "SESSION_ALREADY_TERMINAL"
  ELSE "SESSION_STALE"

SessionFailureResult(id) ==
  IF id \in DOMAIN sessionHistory
  THEN TerminalResult(SessionFailureCode(id), id, KnownVersionOf(id),
         KnownFenceOf(id), KnownDeadlineOf(id), KnownPayloadOf(id),
         sessionHistory[id].outcome)
  ELSE Result("SESSION_STALE", id, NoVersion, 0, NoDeadline, NoPayload)

NewOperation(id) == id \in OperationIds \ DOMAIN operationResults

RecordOperation(id, request, result) ==
  Put(operationResults, id, [request |-> request, result |-> result])

(***************************************************************************
Known identical requests and operation-ID reuse are observations, not state
changes. They are represented by this pure response operator. Stuttering in
Spec represents receiving either response without changing algorithm state.
***************************************************************************)
OperationReply(id, request) ==
  IF operationResults[id].request = request
  THEN operationResults[id].result
  ELSE Result("OPERATION_ID_REUSED", NoSession, NoVersion, 0,
              NoDeadline, NoPayload)

OtherOperationKind(kind) ==
  IF kind = "BEGIN_WRITE" THEN "COMPLETE_WRITE" ELSE "BEGIN_WRITE"

MismatchedRequest(request) ==
  [request EXCEPT !.kind = OtherOperationKind(request.kind)]

RecordOnly(id, request, result) ==
  /\ operationResults' = RecordOperation(id, request, result)
  /\ UNCHANGED CoreVars

BeginWriteCode(expectedBase) ==
  IF recovery # NoRecovery THEN "RECOVERY_REQUIRED"
  ELSE IF writeSession # NoSession THEN "WRITE_ACTIVE"
  ELSE IF DOMAIN liveReadSessions # {} THEN "LIVE_READ_ACTIVE"
  ELSE IF expectedBase # activeVersion THEN "EXPECTED_BASE_MISMATCH"
  ELSE IF /\ activeVersion # NoVersion
          /\ policy.missingCurrent = RequireCurrent
          /\ activeVersion \notin DOMAIN snapshots
       THEN "CURRENT_SNAPSHOT_REQUIRED"
  ELSE IF /\ snapshotSession # NoSession
          /\ policy.writerDuringSnapshot = BlockWriter
       THEN "SNAPSHOT_ACTIVE"
  ELSE Admit

BeginLiveReadCode ==
  IF recovery # NoRecovery THEN "RECOVERY_REQUIRED"
  ELSE IF activeVersion = NoVersion THEN "NO_ACTIVE_VERSION"
  ELSE IF writeSession # NoSession THEN "WRITE_ACTIVE"
  ELSE Admit

BeginSnapshotCode ==
  IF policy.snapshotSupport = SnapshotDisabled
  THEN "SNAPSHOT_SUPPORT_DISABLED"
  ELSE IF recovery # NoRecovery THEN "RECOVERY_REQUIRED"
  ELSE IF activeVersion = NoVersion THEN "NO_ACTIVE_VERSION"
  ELSE IF writeSession # NoSession THEN "WRITE_ACTIVE"
  ELSE IF activeVersion \in DOMAIN snapshots THEN "SNAPSHOT_ALREADY_EXISTS"
  ELSE IF snapshotSession # NoSession THEN "SNAPSHOT_ACTIVE"
  ELSE Admit

BeginRecoveryCode ==
  IF recovery = NoRecovery THEN "RECOVERY_NOT_REQUIRED"
  ELSE IF recoverySession # NoSession THEN "RECOVERY_ACTIVE"
  ELSE Admit

\* These are TLC representatives for NONE, current/old, and future-stale bases.
\* BeginWrite itself is defined for every VersionOrNone value; larger stale
\* integers take the same EXPECTED_BASE_MISMATCH branch.
ExpectedBases == {NoVersion} \cup (1..(lastAllocatedVersion + 1))
FutureDeadlines == {deadline \in TimeValues : deadline > time}

BeginWrite(op, expectedBase, id, deadline) ==
  LET request == Request("BEGIN_WRITE", NoSession, expectedBase, NoDeadline,
                         NoResolution, NoPayload)
      code == BeginWriteCode(expectedBase)
      candidate == lastAllocatedVersion + 1
      fence == lastFence + 1
      result == Result("WRITE_ADMITTED", id, candidate, fence, deadline,
                       NoPayload)
  IN /\ NewOperation(op)
     /\ IF code # Admit
        THEN RecordOnly(op, request,
             Result(code, NoSession, NoVersion, 0, NoDeadline, NoPayload))
        ELSE /\ FreshSession(id)
             /\ deadline \in FutureDeadlines
             /\ policy' = policy
             /\ activeVersion' = activeVersion
             /\ lastAllocatedVersion' = candidate
             /\ lastFence' = fence
             /\ time' = time
             /\ writeSession' =
                  [ id               |-> id,
                    baseVersion      |-> activeVersion,
                    candidateVersion |-> candidate,
                    deadline         |-> deadline,
                    fence            |-> fence ]
             /\ liveReadSessions' = liveReadSessions
             /\ snapshotSession' = NoSession
             /\ snapshots' = snapshots
             /\ recovery' = recovery
             /\ recoverySession' = recoverySession
             /\ sessionHistory' =
                  IF snapshotSession = NoSession
                  THEN sessionHistory
                  ELSE Put(sessionHistory, snapshotSession.id,
                         HistoryRecord(SnapshotKind, "SNAPSHOT_INVALIDATED",
                           snapshotSession.boundVersion, NoVersion,
                           snapshotSession.deadline, 0, NoPayload))
             /\ operationResults' = RecordOperation(op, request, result)
             /\ activatedVersions' = activatedVersions

CompleteWrite(op, id) ==
  LET request == Request("COMPLETE_WRITE", id, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF writeSession = NoSession \/ writeSession.id # id
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE LET version == writeSession.candidateVersion
                 result == Result("WRITE_COMPLETED", id, version,
                                  writeSession.fence, writeSession.deadline,
                                  NoPayload)
             IN /\ activeVersion' = version
                /\ writeSession' = NoSession
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(WriteKind, "WRITE_COMPLETED", version,
                         writeSession.baseVersion, writeSession.deadline,
                         writeSession.fence, NoPayload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ activatedVersions' = activatedVersions \cup {version}
                /\ UNCHANGED << policy, lastAllocatedVersion, lastFence, time,
                                liveReadSessions, snapshotSession, snapshots,
                                recovery, recoverySession >>

AbortWriteClean(op, id) ==
  LET request == Request("ABORT_WRITE_CLEAN", id, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF writeSession = NoSession \/ writeSession.id # id
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE LET result ==
                   Result("WRITE_ABORTED_CLEAN", id,
                          writeSession.candidateVersion, writeSession.fence,
                          writeSession.deadline, NoPayload)
             IN /\ writeSession' = NoSession
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(WriteKind, "WRITE_ABORTED_CLEAN",
                         writeSession.candidateVersion,
                         writeSession.baseVersion, writeSession.deadline,
                         writeSession.fence, NoPayload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                                lastFence, time, liveReadSessions,
                                snapshotSession, snapshots, recovery,
                                recoverySession, activatedVersions >>

ReportWriteUncertain(op, id) ==
  LET request == Request("REPORT_WRITE_UNCERTAIN", id, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF writeSession = NoSession \/ writeSession.id # id
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE LET result ==
                   Result("WRITE_UNCERTAIN", id,
                          writeSession.candidateVersion, writeSession.fence,
                          writeSession.deadline, NoPayload)
             IN /\ writeSession' = NoSession
                /\ recovery' =
                     [ uncertainVersion |-> writeSession.candidateVersion,
                       previousVersion  |-> writeSession.baseVersion ]
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(WriteKind, "WRITE_UNCERTAIN",
                         writeSession.candidateVersion,
                         writeSession.baseVersion, writeSession.deadline,
                         writeSession.fence, NoPayload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                                lastFence, time, liveReadSessions,
                                snapshotSession, snapshots, recoverySession,
                                activatedVersions >>

BeginLiveRead(op, id, deadline) ==
  LET request == Request("BEGIN_LIVE_READ", NoSession, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
      code == BeginLiveReadCode
      result == Result("LIVE_READ_ADMITTED", id, activeVersion, 0, deadline,
                       NoPayload)
  IN /\ NewOperation(op)
     /\ IF code # Admit
        THEN RecordOnly(op, request,
             Result(code, NoSession, NoVersion, 0, NoDeadline, NoPayload))
        ELSE /\ FreshSession(id)
             /\ deadline \in FutureDeadlines
             /\ liveReadSessions' =
                  Put(liveReadSessions, id,
                    [id |-> id, boundVersion |-> activeVersion,
                     deadline |-> deadline])
             /\ operationResults' = RecordOperation(op, request, result)
             /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                             lastFence, time, writeSession, snapshotSession,
                             snapshots, recovery, recoverySession,
                             sessionHistory, activatedVersions >>

CompleteLiveRead(op, id) ==
  LET request == Request("COMPLETE_LIVE_READ", id, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF id \notin DOMAIN liveReadSessions
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE LET session == liveReadSessions[id]
                 result == Result("LIVE_READ_COMPLETED", id,
                                  session.boundVersion, 0, session.deadline,
                                  NoPayload)
             IN /\ liveReadSessions' = Remove(liveReadSessions, id)
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(LiveReadKind, "LIVE_READ_COMPLETED",
                         session.boundVersion, NoVersion, session.deadline,
                         0, NoPayload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                                lastFence, time, writeSession, snapshotSession,
                                snapshots, recovery, recoverySession,
                                activatedVersions >>

BeginSnapshot(op, id, deadline) ==
  LET request == Request("BEGIN_SNAPSHOT", NoSession, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
      code == BeginSnapshotCode
      result == Result("SNAPSHOT_ADMITTED", id, activeVersion, 0, deadline,
                       NoPayload)
  IN /\ NewOperation(op)
     /\ IF code # Admit
        THEN RecordOnly(op, request,
             Result(code, NoSession, NoVersion, 0, NoDeadline, NoPayload))
        ELSE /\ FreshSession(id)
             /\ deadline \in FutureDeadlines
             /\ snapshotSession' =
                  [id |-> id, boundVersion |-> activeVersion,
                   deadline |-> deadline]
             /\ operationResults' = RecordOperation(op, request, result)
             /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                             lastFence, time, writeSession, liveReadSessions,
                             snapshots, recovery, recoverySession,
                             sessionHistory, activatedVersions >>

SubmitSnapshotCode(id, payload) ==
  IF snapshotSession # NoSession /\ snapshotSession.id = id
  THEN IF snapshotSession.boundVersion # activeVersion
       THEN "SESSION_STALE"
       ELSE IF snapshotSession.boundVersion \in DOMAIN snapshots
            THEN "SNAPSHOT_ALREADY_EXISTS"
            ELSE Publish
  ELSE IF id \in DOMAIN sessionHistory
       THEN IF /\ sessionHistory[id].kind = SnapshotKind
               /\ sessionHistory[id].outcome = "SNAPSHOT_COMPLETED"
            THEN IF sessionHistory[id].payload = payload
                 THEN "SNAPSHOT_COMPLETED"
                 ELSE "SNAPSHOT_PAYLOAD_CONFLICT"
            ELSE SessionFailureCode(id)
       ELSE "SESSION_STALE"

SubmitSnapshot(op, id, payload) ==
  LET request == Request("SUBMIT_SNAPSHOT", id, NoVersion, NoDeadline,
                         NoResolution, payload)
      code == SubmitSnapshotCode(id, payload)
  IN /\ NewOperation(op)
     /\ IF code # Publish
        THEN RecordOnly(op, request,
               IF id \in DOMAIN sessionHistory
                  /\ code # "SNAPSHOT_COMPLETED"
               THEN TerminalResult(code, id, KnownVersionOf(id),
                      KnownFenceOf(id), KnownDeadlineOf(id), NoPayload,
                      sessionHistory[id].outcome)
               ELSE Result(code, id, KnownVersionOf(id), KnownFenceOf(id),
                      KnownDeadlineOf(id),
                      IF code = "SNAPSHOT_COMPLETED"
                      THEN KnownPayloadOf(id) ELSE NoPayload))
        ELSE LET version == snapshotSession.boundVersion
                 result == Result("SNAPSHOT_COMPLETED", id, version, 0,
                                  snapshotSession.deadline, payload)
             IN /\ snapshotSession' = NoSession
                /\ snapshots' = Put(snapshots, version, payload)
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(SnapshotKind, "SNAPSHOT_COMPLETED",
                         version, NoVersion, snapshotSession.deadline, 0,
                         payload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                                lastFence, time, writeSession,
                                liveReadSessions, recovery, recoverySession,
                                activatedVersions >>

AbortSnapshot(op, id) ==
  LET request == Request("ABORT_SNAPSHOT", id, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF snapshotSession = NoSession \/ snapshotSession.id # id
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE LET result ==
                   Result("SNAPSHOT_ABORTED", id,
                          snapshotSession.boundVersion, 0,
                          snapshotSession.deadline, NoPayload)
             IN /\ snapshotSession' = NoSession
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(SnapshotKind, "SNAPSHOT_ABORTED",
                         snapshotSession.boundVersion, NoVersion,
                         snapshotSession.deadline, 0, NoPayload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                                lastFence, time, writeSession,
                                liveReadSessions, snapshots, recovery,
                                recoverySession, activatedVersions >>

BeginRecovery(op, id, deadline) ==
  LET request == Request("BEGIN_RECOVERY", NoSession, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
      code == BeginRecoveryCode
      fence == lastFence + 1
      result ==
        Result("RECOVERY_ADMITTED", id,
               IF recovery = NoRecovery THEN NoVersion
               ELSE recovery.uncertainVersion,
               fence, deadline, NoPayload)
  IN /\ NewOperation(op)
     /\ IF code # Admit
        THEN RecordOnly(op, request,
             Result(code, NoSession, NoVersion, 0, NoDeadline, NoPayload))
        ELSE /\ FreshSession(id)
             /\ deadline \in FutureDeadlines
             /\ lastFence' = fence
             /\ recoverySession' =
                  [id |-> id, deadline |-> deadline, fence |-> fence]
             /\ operationResults' = RecordOperation(op, request, result)
             /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                             time, writeSession, liveReadSessions,
                             snapshotSession, snapshots, recovery,
                             sessionHistory, activatedVersions >>

ResolveRecovery(op, id, resolution) ==
  LET request == Request("RESOLVE_RECOVERY", id, NoVersion, NoDeadline,
                         resolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF recoverySession = NoSession \/ recoverySession.id # id
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE LET accepts == resolution = AcceptWriteVersion
                 nextActive ==
                   IF accepts THEN recovery.uncertainVersion
                   ELSE activeVersion
                 outcome ==
                   IF accepts THEN "RECOVERY_ACCEPTED"
                   ELSE "RECOVERY_RESTORED"
                 result ==
                   Result(outcome, id, nextActive, recoverySession.fence,
                          recoverySession.deadline, NoPayload)
             IN /\ activeVersion' = nextActive
                /\ recovery' = NoRecovery
                /\ recoverySession' = NoSession
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(RecoveryKind, outcome,
                         recovery.uncertainVersion,
                         recovery.previousVersion,
                         recoverySession.deadline, recoverySession.fence,
                         NoPayload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ activatedVersions' =
                     IF accepts
                     THEN activatedVersions \cup {recovery.uncertainVersion}
                     ELSE activatedVersions
                /\ UNCHANGED << policy, lastAllocatedVersion, lastFence, time,
                                writeSession, liveReadSessions,
                                snapshotSession, snapshots >>

AbortRecovery(op, id) ==
  LET request == Request("ABORT_RECOVERY", id, NoVersion, NoDeadline,
                         NoResolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF recoverySession = NoSession \/ recoverySession.id # id
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE LET result ==
                   Result("RECOVERY_ABORTED", id,
                          recovery.uncertainVersion, recoverySession.fence,
                          recoverySession.deadline, NoPayload)
             IN /\ recoverySession' = NoSession
                /\ sessionHistory' =
                     Put(sessionHistory, id,
                       HistoryRecord(RecoveryKind, "RECOVERY_ABORTED",
                         recovery.uncertainVersion,
                         recovery.previousVersion,
                         recoverySession.deadline, recoverySession.fence,
                         NoPayload))
                /\ operationResults' = RecordOperation(op, request, result)
                /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion,
                                lastFence, time, writeSession,
                                liveReadSessions, snapshotSession, snapshots,
                                recovery, activatedVersions >>

RenewSession(op, id, newDeadline) ==
  LET request == Request("RENEW_SESSION", id, NoVersion, newDeadline,
                         NoResolution, NoPayload)
  IN /\ NewOperation(op)
     /\ IF id \notin OpenSessionIds
        THEN RecordOnly(op, request, SessionFailureResult(id))
        ELSE IF newDeadline <= OpenDeadlineOf(id)
             THEN RecordOnly(op, request,
                    Result("DEADLINE_NOT_EXTENDED", id,
                           OpenVersionOf(id), OpenFenceOf(id),
                           OpenDeadlineOf(id), NoPayload))
             ELSE LET result ==
                        Result("SESSION_RENEWED", id, OpenVersionOf(id),
                               OpenFenceOf(id), newDeadline, NoPayload)
                  IN /\ writeSession' =
                           IF id \in WriteIds
                           THEN [writeSession EXCEPT
                                   !.deadline = newDeadline]
                           ELSE writeSession
                     /\ liveReadSessions' =
                           IF id \in DOMAIN liveReadSessions
                           THEN [liveReadSessions EXCEPT
                                   ![id].deadline = newDeadline]
                           ELSE liveReadSessions
                     /\ snapshotSession' =
                           IF id \in SnapshotIds
                           THEN [snapshotSession EXCEPT
                                   !.deadline = newDeadline]
                           ELSE snapshotSession
                     /\ recoverySession' =
                           IF id \in RecoveryIds
                           THEN [recoverySession EXCEPT
                                   !.deadline = newDeadline]
                           ELSE recoverySession
                     /\ operationResults' =
                           RecordOperation(op, request, result)
                     /\ UNCHANGED << policy, activeVersion,
                                     lastAllocatedVersion, lastFence, time,
                                     snapshots, recovery, sessionHistory,
                                     activatedVersions >>

ExpiredOutcome(kind) ==
  IF kind = WriteKind THEN "WRITE_EXPIRED"
  ELSE IF kind = LiveReadKind THEN "LIVE_READ_EXPIRED"
  ELSE IF kind = SnapshotKind THEN "SNAPSHOT_EXPIRED"
  ELSE "RECOVERY_EXPIRED"

ExpiredHistory(id) ==
  HistoryRecord(OpenKindOf(id), ExpiredOutcome(OpenKindOf(id)),
                OpenVersionOf(id), OpenBaseOf(id), OpenDeadlineOf(id),
                OpenFenceOf(id), NoPayload)

DueSessionIds(newTime) ==
  {id \in OpenSessionIds : OpenDeadlineOf(id) <= newTime}

WriteExpires(newTime) ==
  writeSession # NoSession /\ writeSession.deadline <= newTime

AdvanceTime(newTime) ==
  LET due == DueSessionIds(newTime)
      expiredEntries == [id \in due |-> ExpiredHistory(id)]
  IN /\ newTime \in TimeValues
     /\ newTime >= time
     /\ time' = newTime
     /\ writeSession' =
          IF WriteExpires(newTime) THEN NoSession ELSE writeSession
     /\ liveReadSessions' = RemoveAll(liveReadSessions, due)
     /\ snapshotSession' =
          IF snapshotSession # NoSession /\ snapshotSession.id \in due
          THEN NoSession ELSE snapshotSession
     /\ recoverySession' =
          IF recoverySession # NoSession /\ recoverySession.id \in due
          THEN NoSession ELSE recoverySession
     /\ recovery' =
          IF WriteExpires(newTime)
          THEN [ uncertainVersion |-> writeSession.candidateVersion,
                 previousVersion  |-> writeSession.baseVersion ]
          ELSE recovery
     /\ sessionHistory' = Override(sessionHistory, expiredEntries)
     /\ UNCHANGED << policy, activeVersion, lastAllocatedVersion, lastFence,
                     snapshots, operationResults, activatedVersions >>

(***************************************************************************
Stored snapshot retrieval is an atomic read-only observation and therefore is
not a Next action and needs no operation ID.
***************************************************************************)
LatestStoredVersion ==
  CHOOSE version \in DOMAIN snapshots :
    \A other \in DOMAIN snapshots : other <= version

SnapshotResponse(code, snapshotVersion, payload) ==
  [ code                  |-> code,
    snapshotVersion       |-> snapshotVersion,
    observedActiveVersion |-> activeVersion,
    recoveryRequired      |-> recovery # NoRecovery,
    stale                 |->
      IF snapshotVersion = NoVersion \/ activeVersion = NoVersion
      THEN FALSE ELSE snapshotVersion < activeVersion,
    payload               |-> payload ]

SnapshotReadCodes ==
  {"SNAPSHOT_SUPPORT_DISABLED", "SNAPSHOT_COMPLETED", "SNAPSHOT_NOT_FOUND",
   "NO_ACTIVE_VERSION", "WRITE_IN_PROGRESS",
   "CURRENT_SNAPSHOT_UNAVAILABLE"}

SnapshotReadResultType ==
  [ code                  : SnapshotReadCodes,
    snapshotVersion       : VersionOrNone,
    observedActiveVersion : VersionOrNone,
    recoveryRequired      : BOOLEAN,
    stale                 : BOOLEAN,
    payload               : PayloadOrNone ]

GetSnapshot(selector, requestedVersion, duringWrite) ==
  IF policy.snapshotSupport = SnapshotDisabled
  THEN SnapshotResponse("SNAPSHOT_SUPPORT_DISABLED", NoVersion, NoPayload)
  ELSE IF selector = ByVersion
       THEN IF requestedVersion \in DOMAIN snapshots
            THEN SnapshotResponse("SNAPSHOT_COMPLETED", requestedVersion,
                                  snapshots[requestedVersion])
            ELSE SnapshotResponse("SNAPSHOT_NOT_FOUND", NoVersion, NoPayload)
  ELSE IF activeVersion = NoVersion
       THEN SnapshotResponse("NO_ACTIVE_VERSION", NoVersion, NoPayload)
  ELSE IF /\ writeSession # NoSession
          /\ duringWrite = RejectIfWriting
       THEN SnapshotResponse("WRITE_IN_PROGRESS", NoVersion, NoPayload)
  ELSE IF selector = Current
       THEN IF activeVersion \in DOMAIN snapshots
            THEN SnapshotResponse("SNAPSHOT_COMPLETED", activeVersion,
                                  snapshots[activeVersion])
            ELSE SnapshotResponse("CURRENT_SNAPSHOT_UNAVAILABLE", NoVersion,
                                  NoPayload)
  ELSE IF DOMAIN snapshots = {}
       THEN SnapshotResponse("SNAPSHOT_NOT_FOUND", NoVersion, NoPayload)
       ELSE LET version == LatestStoredVersion
            IN SnapshotResponse("SNAPSHOT_COMPLETED", version,
                                snapshots[version])

Init ==
  /\ policy \in ValidPolicies
  /\ activeVersion = NoVersion
  /\ lastAllocatedVersion = 0
  /\ lastFence = 0
  /\ time = 0
  /\ writeSession = NoSession
  /\ liveReadSessions = EmptyFunction
  /\ snapshotSession = NoSession
  /\ snapshots = EmptyFunction
  /\ recovery = NoRecovery
  /\ recoverySession = NoSession
  /\ sessionHistory = EmptyFunction
  /\ operationResults = EmptyFunction
  /\ activatedVersions = {}

Next ==
  \/ \E op \in OperationIds,
        expectedBase \in ExpectedBases,
        id \in SessionIds,
        deadline \in TimeValues :
       BeginWrite(op, expectedBase, id, deadline)
  \/ \E op \in OperationIds, id \in SessionIds : CompleteWrite(op, id)
  \/ \E op \in OperationIds, id \in SessionIds : AbortWriteClean(op, id)
  \/ \E op \in OperationIds, id \in SessionIds :
       ReportWriteUncertain(op, id)
  \/ \E op \in OperationIds, id \in SessionIds,
        deadline \in TimeValues :
       BeginLiveRead(op, id, deadline)
  \/ \E op \in OperationIds, id \in SessionIds : CompleteLiveRead(op, id)
  \/ \E op \in OperationIds, id \in SessionIds,
        deadline \in TimeValues :
       BeginSnapshot(op, id, deadline)
  \/ \E op \in OperationIds, id \in SessionIds, payload \in Payloads :
       SubmitSnapshot(op, id, payload)
  \/ \E op \in OperationIds, id \in SessionIds : AbortSnapshot(op, id)
  \/ \E op \in OperationIds, id \in SessionIds,
        deadline \in TimeValues :
       RenewSession(op, id, deadline)
  \/ \E op \in OperationIds, id \in SessionIds,
        deadline \in TimeValues :
       BeginRecovery(op, id, deadline)
  \/ \E op \in OperationIds, id \in SessionIds,
        resolution \in RecoveryResolutions :
       ResolveRecovery(op, id, resolution)
  \/ \E op \in OperationIds, id \in SessionIds : AbortRecovery(op, id)
  \/ \E newTime \in TimeValues : AdvanceTime(newTime)

Spec == Init /\ [][Next]_vars

(***************************************************************************
State invariants. Representation already enforces at most one write,
snapshot-generation, and recovery session.
***************************************************************************)
TypeOK ==
  /\ policy \in ValidPolicies
  /\ activeVersion \in VersionOrNone
  /\ lastAllocatedVersion \in Nat
  /\ lastFence \in Nat
  /\ time \in TimeValues
  /\ writeSession \in {NoSession} \cup WriteSessionType
  /\ DOMAIN liveReadSessions \subseteq SessionIds
  /\ \A id \in DOMAIN liveReadSessions :
       liveReadSessions[id] \in LiveReadSessionType
  /\ snapshotSession \in {NoSession} \cup SnapshotSessionType
  /\ DOMAIN snapshots \subseteq VersionValues
  /\ \A version \in DOMAIN snapshots : snapshots[version] \in Payloads
  /\ recovery \in {NoRecovery} \cup RecoveryType
  /\ recoverySession \in {NoSession} \cup RecoverySessionType
  /\ DOMAIN sessionHistory \subseteq SessionIds
  /\ \A id \in DOMAIN sessionHistory :
       sessionHistory[id] \in HistoryRecordType
  /\ DOMAIN operationResults \subseteq OperationIds
  /\ \A op \in DOMAIN operationResults :
       operationResults[op] \in OperationEntryType
  /\ activatedVersions \subseteq VersionValues

SessionIdentitySafety ==
  /\ WriteIds \cap DOMAIN liveReadSessions = {}
  /\ WriteIds \cap SnapshotIds = {}
  /\ WriteIds \cap RecoveryIds = {}
  /\ DOMAIN liveReadSessions \cap SnapshotIds = {}
  /\ DOMAIN liveReadSessions \cap RecoveryIds = {}
  /\ SnapshotIds \cap RecoveryIds = {}
  /\ OpenSessionIds \cap DOMAIN sessionHistory = {}

OpenDeadlineSafety ==
  \A id \in OpenSessionIds : OpenDeadlineOf(id) > time

CoordinationSafety ==
  /\ IF writeSession # NoSession
     THEN /\ DOMAIN liveReadSessions = {}
          /\ snapshotSession = NoSession
          /\ recovery = NoRecovery
          /\ recoverySession = NoSession
     ELSE TRUE
  /\ IF recovery # NoRecovery
     THEN /\ writeSession = NoSession
          /\ DOMAIN liveReadSessions = {}
          /\ snapshotSession = NoSession
     ELSE recoverySession = NoSession
  /\ \A id \in DOMAIN liveReadSessions :
       /\ activeVersion # NoVersion
       /\ liveReadSessions[id].boundVersion = activeVersion
  /\ IF snapshotSession # NoSession
     THEN /\ activeVersion # NoVersion
          /\ snapshotSession.boundVersion = activeVersion
          /\ snapshotSession.boundVersion \notin DOMAIN snapshots
     ELSE TRUE

VersionSafety ==
  /\ activeVersion = NoVersion \/ activeVersion \in activatedVersions
  /\ activatedVersions \subseteq 1..lastAllocatedVersion
  /\ activeVersion = NoVersion \/ activeVersion <= lastAllocatedVersion
  /\ IF activeVersion = NoVersion
     THEN activatedVersions = {}
     ELSE activatedVersions \subseteq 1..activeVersion
  /\ IF writeSession # NoSession
     THEN /\ writeSession.baseVersion = activeVersion
          /\ writeSession.candidateVersion = lastAllocatedVersion
          /\ (writeSession.baseVersion = NoVersion
              \/ writeSession.candidateVersion > writeSession.baseVersion)
     ELSE TRUE
  /\ IF recovery # NoRecovery
     THEN /\ recovery.previousVersion = activeVersion
          /\ recovery.uncertainVersion <= lastAllocatedVersion
          /\ recovery.uncertainVersion \notin activatedVersions
          /\ (recovery.previousVersion = NoVersion
              \/ recovery.uncertainVersion > recovery.previousVersion)
     ELSE recoverySession = NoSession

SnapshotSafety ==
  /\ DOMAIN snapshots \subseteq activatedVersions
  /\ \A version \in DOMAIN snapshots :
       /\ activeVersion # NoVersion
       /\ version <= activeVersion
  /\ IF policy.snapshotSupport = SnapshotDisabled
     THEN /\ DOMAIN snapshots = {}
          /\ snapshotSession = NoSession
     ELSE TRUE
  /\ IF /\ writeSession # NoSession
          /\ writeSession.baseVersion # NoVersion
          /\ policy.missingCurrent = RequireCurrent
     THEN writeSession.baseVersion \in DOMAIN snapshots
     ELSE TRUE
  /\ \A id \in DOMAIN sessionHistory :
       IF /\ sessionHistory[id].kind = SnapshotKind
             /\ sessionHistory[id].outcome = "SNAPSHOT_COMPLETED"
       THEN /\ sessionHistory[id].version \in DOMAIN snapshots
            /\ snapshots[sessionHistory[id].version] =
                 sessionHistory[id].payload
       ELSE TRUE
  /\ \A version \in DOMAIN snapshots :
       \E id \in DOMAIN sessionHistory :
         /\ sessionHistory[id].kind = SnapshotKind
         /\ sessionHistory[id].outcome = "SNAPSHOT_COMPLETED"
         /\ sessionHistory[id].version = version
         /\ sessionHistory[id].payload = snapshots[version]

HistorySafety ==
  \A id \in DOMAIN sessionHistory :
    HistoryOutcomeMatches(sessionHistory[id].kind,
                          sessionHistory[id].outcome)

OperationReplySafety ==
  \A op \in DOMAIN operationResults :
    /\ OperationReply(op, operationResults[op].request) =
         operationResults[op].result
    /\ OperationReply(op,
         MismatchedRequest(operationResults[op].request)).code =
         "OPERATION_ID_REUSED"

TerminalSnapshotSubmissionSafety ==
  \A id \in DOMAIN sessionHistory :
    IF sessionHistory[id].kind = SnapshotKind
    THEN IF sessionHistory[id].outcome = "SNAPSHOT_COMPLETED"
         THEN \A payload \in Payloads :
                IF payload = sessionHistory[id].payload
                THEN SubmitSnapshotCode(id, payload) = "SNAPSHOT_COMPLETED"
                ELSE SubmitSnapshotCode(id, payload) =
                       "SNAPSHOT_PAYLOAD_CONFLICT"
         ELSE \A payload \in Payloads :
                SubmitSnapshotCode(id, payload) = SessionFailureCode(id)
    ELSE TRUE

SnapshotReadSafety ==
  \A selector \in SnapshotSelectors,
     requestedVersion \in 1..(lastAllocatedVersion + 1),
     duringWrite \in DuringWriteChoices :
    LET response == GetSnapshot(selector, requestedVersion, duringWrite)
    IN /\ response \in SnapshotReadResultType
       /\ response.observedActiveVersion = activeVersion
       /\ response.recoveryRequired = (recovery # NoRecovery)
       /\ IF response.code = "SNAPSHOT_COMPLETED"
          THEN /\ response.snapshotVersion \in DOMAIN snapshots
               /\ response.payload = snapshots[response.snapshotVersion]
          ELSE /\ response.snapshotVersion = NoVersion
               /\ response.payload = NoPayload
       /\ IF response.code = "SNAPSHOT_COMPLETED" /\ selector = Current
          THEN response.snapshotVersion = activeVersion
          ELSE TRUE
       /\ IF response.code = "SNAPSHOT_COMPLETED"
             /\ selector = LatestAvailable
          THEN \A version \in DOMAIN snapshots :
                 version <= response.snapshotVersion
          ELSE TRUE
       /\ response.stale =
            IF response.snapshotVersion = NoVersion
               \/ activeVersion = NoVersion
            THEN FALSE
            ELSE response.snapshotVersion < activeVersion
       /\ IF policy.snapshotSupport = SnapshotDisabled
          THEN response.code = "SNAPSHOT_SUPPORT_DISABLED"
          ELSE TRUE
       /\ IF policy.snapshotSupport = SnapshotEnabled
             /\ selector = ByVersion
          THEN IF requestedVersion \in DOMAIN snapshots
               THEN response.code = "SNAPSHOT_COMPLETED"
               ELSE response.code = "SNAPSHOT_NOT_FOUND"
          ELSE TRUE
       /\ IF policy.snapshotSupport = SnapshotEnabled
             /\ selector # ByVersion
             /\ activeVersion = NoVersion
          THEN response.code = "NO_ACTIVE_VERSION"
          ELSE TRUE
       /\ IF policy.snapshotSupport = SnapshotEnabled
             /\ selector # ByVersion
             /\ activeVersion # NoVersion
             /\ writeSession # NoSession
             /\ duringWrite = RejectIfWriting
          THEN response.code = "WRITE_IN_PROGRESS"
          ELSE TRUE

FencedHistoryIds ==
  {id \in DOMAIN sessionHistory :
     sessionHistory[id].kind \in {WriteKind, RecoveryKind}}

FenceSafety ==
  /\ \A id \in FencedHistoryIds :
       /\ sessionHistory[id].fence > 0
       /\ sessionHistory[id].fence <= lastFence
  /\ \A left, right \in FencedHistoryIds :
       left # right =>
         sessionHistory[left].fence # sessionHistory[right].fence
  /\ IF writeSession # NoSession
     THEN /\ writeSession.fence <= lastFence
          /\ \A id \in FencedHistoryIds :
               writeSession.fence > sessionHistory[id].fence
     ELSE TRUE
  /\ IF recoverySession # NoSession
     THEN /\ recoverySession.fence <= lastFence
          /\ \A id \in FencedHistoryIds :
               recoverySession.fence > sessionHistory[id].fence
     ELSE TRUE

Safety ==
  /\ TypeOK
  /\ SessionIdentitySafety
  /\ OpenDeadlineSafety
  /\ CoordinationSafety
  /\ VersionSafety
  /\ SnapshotSafety
  /\ HistorySafety
  /\ OperationReplySafety
  /\ TerminalSnapshotSubmissionSafety
  /\ SnapshotReadSafety
  /\ FenceSafety

(***************************************************************************
Action/temporal safety: counters and active version never decrease; policy,
stored snapshots, terminal session history, and operation results are
permanent. No fairness is asserted by this module.
***************************************************************************)
ExtendsImmutably(old, new) ==
  \A key \in DOMAIN old :
    /\ key \in DOMAIN new
    /\ new[key] = old[key]

MonotonicStep ==
  /\ policy' = policy
  /\ lastAllocatedVersion' >= lastAllocatedVersion
  /\ lastAllocatedVersion' <= lastAllocatedVersion + 1
  /\ lastFence' >= lastFence
  /\ lastFence' <= lastFence + 1
  /\ time' >= time
  /\ activatedVersions \subseteq activatedVersions'
  /\ IF activeVersion = NoVersion
     THEN TRUE
     ELSE /\ activeVersion' # NoVersion
          /\ activeVersion' >= activeVersion
  /\ IF lastAllocatedVersion' = lastAllocatedVersion + 1
     THEN /\ writeSession' # NoSession
          /\ writeSession'.candidateVersion = lastAllocatedVersion'
          /\ writeSession'.fence = lastFence'
     ELSE TRUE
  /\ IF lastFence' = lastFence + 1
     THEN \/ /\ writeSession' # NoSession
               /\ writeSession'.fence = lastFence'
          \/ /\ recoverySession' # NoSession
               /\ recoverySession'.fence = lastFence'
     ELSE TRUE
  /\ IF activeVersion' # activeVersion
     THEN /\ activeVersion' # NoVersion
          /\ \E id \in DOMAIN sessionHistory' \ DOMAIN sessionHistory :
               \/ /\ sessionHistory'[id].kind = WriteKind
                  /\ sessionHistory'[id].outcome = "WRITE_COMPLETED"
                  /\ sessionHistory'[id].version = activeVersion'
               \/ /\ sessionHistory'[id].kind = RecoveryKind
                  /\ sessionHistory'[id].outcome = "RECOVERY_ACCEPTED"
                  /\ sessionHistory'[id].version = activeVersion'
     ELSE TRUE
  /\ ExtendsImmutably(snapshots, snapshots')
  /\ ExtendsImmutably(sessionHistory, sessionHistory')
  /\ ExtendsImmutably(operationResults, operationResults')

TemporalSafety == [][MonotonicStep]_vars

(***************************************************************************
The three TLC model-value domains are unordered and disjoint, so their product
permutation is a valid symmetry set for the accompanying finite model.
***************************************************************************)
ModelSymmetry ==
  {Override(Override(sessionPermutation, operationPermutation),
            payloadPermutation) :
     sessionPermutation \in Permutations(SessionIds),
     operationPermutation \in Permutations(OperationIds),
     payloadPermutation \in Permutations(Payloads)}

=============================================================================
