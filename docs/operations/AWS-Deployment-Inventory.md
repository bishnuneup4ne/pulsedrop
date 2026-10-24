# AWS Deployment Inventory

Phase 0 output. Recorded from read-only inspection on **2026-10-24**, from a fresh inspection of
the host and of `main` at `98c2a40`. Nothing on the host or in the repository was changed to
produce this file.

> Identifiers that would matter to an attacker — full instance id, account id, public IP and AMI —
> are deliberately **not** written into this public repository. They are recorded in the working
> session notes instead.

---

## 1. The headline finding

**No part of PulseDrop is deployed on AWS.**

The brief assumed a deployment might exist and told me to discover it rather than invent one. The
discovery result is that it does not:

* No PulseDrop process, service, container, unit file, or build artefact exists on the host.
* No web server of any kind is installed — no Nginx, Caddy, or Apache.
* Nothing in the repository references AWS: no SDK dependency, no IaC, no deploy workflow, no
  Terraform/CloudFormation, no ECS/Lambda configuration. The only matches for a search of
  `aws|ec2|s3|lambda` in source are Kotlin **language** lambdas.
* The only running container (`floodstack-proxyd`) is an unrelated SOCKS proxy from another project,
  bound to loopback. It is not PulseDrop's and I have not touched it.

PulseDrop's server is a **foreground HTTP server inside an Android app, bound to a phone on a local
network**. The brief's rule 6 — "do not assume the Android LAN server is meant to run on AWS" — is
not a caution here, it is the answer: it does not run there, and moving it there would be an
architecture change, not a deployment fix.

### What the AWS host actually is

| | |
| --- | --- |
| Role | **Development / build / agent machine.** Not a production server. |
| Provider form | AWS EC2, Xen HVM domU, `cloud-amd64` kernel |
| Type | `t2.2xlarge` (8 vCPU, 31 GiB RAM) |
| OS | Debian 13.7 (trixie) |
| Region | `ap-southeast-2` (Sydney), AZ `b` |
| Disk | 99 GiB root, 4.6 GiB used, 90 GiB free |
| Reachable state | SSH only; no inbound HTTP |
| Instance IAM role | **None attached** — no credentials via IMDS |
| AWS CLI | Binary present, unconfigured (`~/.aws` absent, no region env) |

Because there is no instance role and no configured credentials, **I could not and did not inventory
AWS control plane** (security groups, EBS snapshots, alarms). Those need owner credentials. See §6.

---

## 2. Deployment map of what PulseDrop actually ships

| Component | Where it runs | Public surface | Notes |
| --- | --- | --- | --- |
| Android app + LAN server (`PulseDropServer.kt`) | On the user's phone | The LAN only | The product. HTTP on 8080, HTTPS on a second port with a process-lifetime self-signed cert (`WebTls.kt`). |
| Browser client (`WebClientHtml.kt`, 4 752 lines) | Served by the phone to LAN tabs | The LAN only | No CDN, no internet dependency — correct for offline use. |
| Calls / relay / pairing | Phone-to-phone on the LAN | The LAN only | Relay mailbox is hosted by one phone, not by a cloud. |
| APK releases | **GitHub Releases** | Public | `PulseDrop-<version>.apk` + SHA-256 in release notes; VirusTotal per release. This is the only real public distribution surface. |
| Update check | `api.github.com/repos/<repo>/releases` | Public GitHub | `UpdateManager.kt:61` — no self-hosted endpoint. |
| Install stats site (`site/`) | **Not deployed anywhere** | Nothing | `INSTALL_PING_URL` and `INSTALL_STATS_URL` are both `""` in `app/build.gradle.kts:39-40`, so the reporter stays silent in every shipped build. `dev/stats-local/server.mjs` is a local stub only. |
| GitHub Actions | `ci.yml`, `virustotal-scan.yml` | GitHub-hosted | Unit tests, lint, unsigned release assembly. |

**Consequence for this engagement:** Phase 7 (AWS operations) is **not applicable to any current
component**, and I will not build cloud infrastructure for a local-first product. Phases 2, 3, 4, 6
and 8 are where the real risk and real work are, because the running production system is the APK
sitting on users' phones.

---

## 3. Host exposure (redacted)

Listening sockets on the AWS host, all of which belong to the machine and none to PulseDrop:

| Port | Bind | Assessment |
| --- | --- | --- |
| 22/tcp | `0.0.0.0` and `::` | Only internet-reachable listener. Effective sshd config: `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PubkeyAuthentication yes`, `PermitRootLogin without-password`. Key-only — acceptable posture. **Still open to the whole internet**, which is a security-group question, not an sshd question (§6). |
| 53 | `127.0.0.53/54` only | systemd-resolved, loopback. Correct. |
| 5355/tcp+udp | `0.0.0.0`/`::` | systemd-llmnr. Normally should be LAN-scoped; reachable through the interface. Low severity, worth tightening. |
| 9050 | `127.0.0.1` only | Loopback SOCKS proxy for the unrelated container. Correct. |

Firewall: 23 iptables rules present, 3 containing DROP/REJECT. Not dumped here.

Good practice already in place on the host: unattended-upgrades is active, no secrets or keystores
are tracked in git, and `.gitignore` explicitly excludes `*.jks`, `*.keystore`, `*.p12`,
`signing.properties`, `.env`, and `local.properties`.

---

## 4. Toolchain baseline — what is and is not installed

This is the practical blocker for "run the tests".

| Needed for the work | Present? | Consequence |
| --- | --- | --- |
| JDK 21 (CI uses Temurin 21) | **No** — `java` not found, no `/usr/lib/jvm` | `./gradlew test` cannot run at all. Every claim below about tests is unverified until this exists. |
| Android SDK (platform 36, build-tools) | **No** — no SDK dirs, no `ANDROID_HOME`, no `local.properties` | `assembleRelease` and `lintRelease` cannot run. |
| Gradle | Wrapper present (`gradlew`, Gradle 9.3.1 pinned) — correct; no system Gradle, and none should be installed | Use `./gradlew` only. |
| Node.js | **No** | Only needed later if `site/` is deployed or browser asset bundling is introduced. Not needed for P0 work. |
| `gh` CLI | **No** | Cannot open issues or PRs from the CLI; see §5. |
| AWS CLI | Binary present, unconfigured | Not needed for the P0 work. Needed only if the owner wants cloud control-plane changes. |
| sudo | Available passwordless | Package installs are possible — **I have not installed anything yet.** |
| Outbound network | `services.gradle.org` 200, `dl.google.com` 200 | Provenance for JDK + SDK is reachable, so a real build is achievable here. |

Reproducible build requirement, read off the project rather than guessed: Gradle 9.3.1 (wrapper),
`compileSdk 36.1`, `minSdk 24`, `targetSdk 36`, `sourceCompatibility/targetCompatibility Java 11`,
CI JDK **21**, `app.versionName = "2.0.2"`, `versionCode = 12`.

---

## 5. Git and GitHub state

* Branch `main`, HEAD `98c2a40`, clean except two files that are **already in progress and were not
  created by me**: `docs/Home.md` (modified, adds one index link) and `docs/Authorization-Matrix.md`
  (untracked, 276 lines, substantial and correct against the code). I preserved both.
* `git ls-remote origin` succeeds unauthenticated — the repository is public, **read** access works.
* **No push, no `gh`, no stored credentials.** No credential helper, no `~/.git-credentials`, no
  `~/.config/gh`. I therefore cannot open issues or PRs, and I have not tried to.
* `git config user.name` / `user.email` are unset — commits need an identity the owner approves.
* Per the brief's rule 10 I will not push to `main`; branches and PRs only, after auth exists.

---

## 6. Backups, rollback, and what only the owner can do

**Backup status: NOT VERIFIED.** I could not confirm an EBS snapshot exists, because there are no
AWS credentials on this host. I will not claim a backup is restorable, and no production-impacting
step may proceed on that assumption.

Rollback for the repository work is trivial and is already available: this is documentation and
Android-source work on a feature branch, and `git reset --hard 98c2a40` (or closing the PR) reverts
it. There is no database, no schema migration, and no running service to roll back, precisely
because nothing is deployed.

The owner must decide/do the following; I cannot substitute for any of it:

1. **GitHub access** — authenticate the CLI, or tell me to prepare patches for you to apply.
2. **Security group for this instance** — confirm whether 22/tcp is `0.0.0.0/0`. Restricting it is a
   control-plane change I am not authorised to make.
3. **EBS snapshot / AMI backup** of this dev box, if its contents matter.
4. **Whether install stats should ever go live.** `site/` exists but is undeployed and the app's ping
   URLs are blank. Deploying it is a product decision with a privacy cost (the brief's §9), not a
   fix.

---

## 7. Health checks, logs, limits

There is no deployed service to health-check. The meaningful equivalents:

* **App-level health** is the phone's server screen; `GET /api/info` is the closest thing to a health
  endpoint, and it is intentionally public. It returns `tlsError` free text (`PulseDropServer.kt:1137`)
  — that must be tested to never contain key material, paths, or file names.
* **Logs** are logcat, and PRD P1/P0 items on "no secrets in logs" are untested.
* **Resource limits** on the host: 90 GiB free disk, 30 GiB RAM available — ample for a Gradle build
  with an SDK, but the first build will download several gigabytes.

## 8. Production vs staging

Neither exists on AWS. The only distinction worth keeping is: **`main` + GitHub Releases = what users
run.** A staging deployment for a phone app means a test APK sideloaded onto a test device, which is
the honest version of "staging" here and is what I will propose in the runbook rather than a cloud
environment that would serve no purpose.
