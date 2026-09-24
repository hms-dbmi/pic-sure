# Contributing to PIC-SURE

This guide is for contributors outside the Avillach Lab. It covers the code of conduct, how to
file an issue, which repository your change belongs in, and how to open a pull request.

PIC-SURE is spread across several repositories. If you are not sure which one your change
belongs in, start with [Where does my change go?](#where-does-my-change-go).

## Code of conduct

When you contribute to a PIC-SURE project, you agree to abide by our
[Code of Conduct](https://github.com/hms-dbmi/pic-sure/blob/main/CODE_OF_CONDUCT.md). Report
unacceptable behavior to avillach_lab_developers@googlegroups.com.

## Ways to contribute

Aside from pull requests, our main channel is the "Issues" tab of the repository you are working
in. Use them to report a bug, suggest an enhancement, or ask a question. Once we have received an issue submission, we will be in contact
within 5 business days.

Documentation is a contribution like any other. If something in a README or in this guide is
wrong or missing, open an issue or send a pull request against that file.

Most changes do not need the whole platform running. Working in the repository your change belongs
to is faster, and the table below gives the build and test command for each one. If you do want the
full stack, follow the install steps in the
[pic-sure-all-in-one README](https://github.com/hms-dbmi/pic-sure-all-in-one/blob/main/README.md).

## Where does my change go?

| Repo | What it is | Build and test |
|---|---|---|
| [`pic-sure`](https://github.com/hms-dbmi/pic-sure) | API mono repo, Java | `mvn -T1C install` |
| [`PIC-SURE-Frontend`](https://github.com/hms-dbmi/PIC-SURE-Frontend) | SvelteKit UI | `pnpm run check && pnpm run test:unit` |
| [`pic-sure-python-adapter-hpds`](https://github.com/hms-dbmi/pic-sure-python-adapter-hpds) | Python client | `python -m unittest discover tests` |
| [`pic-sure-r-adapter-hpds`](https://github.com/hms-dbmi/pic-sure-r-adapter-hpds) | R client, `picsure` package | `testthat::test_dir("tests/testthat")` |
| [`pic-sure-all-in-one`](https://github.com/hms-dbmi/pic-sure-all-in-one) | Full-stack installer | Follow the install steps in its README. Asks for 32 GB of RAM and 8 cores. |
| [`pic-sure-bdc-infrastructure`](https://github.com/hms-dbmi/pic-sure-bdc-infrastructure) | Terraform | Per-directory `terraform plan`. Needs lab AWS access. |
| [`avillachlab-jenkins`](https://github.com/hms-dbmi/avillachlab-jenkins) | CI infrastructure | Per-directory. Needs lab AWS access. |

Several repos were archived in September 2026 and are read-only. Their code moved into
`pic-sure`: `pic-sure-common` to `libs/pic-sure-commons`, `PIC-SURE-Logging-Client` to
`libs/pic-sure-logging-client`, `PIC-SURE-Visualization` to `services/pic-sure-visualization-service`,
`PIC-SURE-Logging` to `services/pic-sure-logging`, `pic-sure-auth-microapp` to
`services/pic-sure-auth-microapp`, and `picsure-dictionary` to `services/picsure-dictionary`.

`pic-sure-bdc-infrastructure` and `avillachlab-jenkins` deploy Avillach Lab infrastructure and
need credentials we cannot share. You will not be able to build or test them. If you spot a
problem in either, please open an issue rather than a pull request.

The `pic-sure` build requires JDK 25 and fails early on anything older. The repository pins the
exact version in `.sdkmanrc`, and Maven is run from the repository root.

## Reporting bugs

If you find a bug, first take a look through the other submitted issues to see if anyone has
reported the same bug yet. If so, feel free to comment to let us know that it is also impacting
you so we know to elevate the issue, and to add any additional details you may have.

If no other issue has been raised, select "New Issue". The title should be a short summary of the
bug. The body should say what you did, what you expected, and what happened instead, plus
anything that helps us reproduce it: the steps you took, your browser version and operating
system, error output, screenshots, or code. Leave out anything sensitive, and never paste patient
data or credentials into an issue.

## Suggesting enhancements

If you have an idea for an enhancement, first take a look through the other submitted issues to
see if anyone has suggested the same or a similar improvement. If so, comment to let us know that
you would also like that feature, so we can gauge interest.

If no one else has suggested it, select "New Issue". The title should be a short summary of the
requested feature, and the body should give the details that make the case: the use case it
serves, who it helps, and any similar feature in another application we can look at.

## Asking questions

If you have a question about how to use PIC-SURE, first look through the
[PIC-SURE User Guide](https://pic-sure.gitbook.io/pic-sure) and the Issues tab to see whether it
is already answered. If you still need help, select "New Issue", put your question in the title,
and add any context in the body.

## Submitting a pull request

In general, we follow the ["fork-and-pull" Git workflow](https://github.com/susam/gitpr)

1. Fork the repository to your own Github account
2. Clone the project to your machine
3. Create a branch locally with a succinct but descriptive name
4. Commit changes to the branch
5. Follow any formatting and testing guidelines specific to this repo
6. Push changes to your fork
7. Open a PR in our repository and follow the
   [pull request template](https://github.com/hms-dbmi/pic-sure/blob/main/.github/pull_request_template.md)
   so that we can efficiently review the changes.

Anyone can submit a pull request for PIC-SURE applications. Each pull request should include a
unit test for any new code, as well as pass any available Github Action tests on the relevant
repo before it can be submitted to the PIC-SURE development team. If you believe there to be an
issue with the Github tests, you can raise a bug report issue with the details so we know to take
a look.

We ask that if submitting changes for multiple repositories for one connected issue, that you
make sure the tests pass for all repos before submitting to avoid confusion.

Keep each pull request to one logical change. Two unrelated fixes in one branch take longer to
review than the same two fixes in separate branches, and a reviewer cannot approve half of a
pull request.

## Review and merge

The automated checks run as soon as you open the pull request. Fix anything they flag. We start
reviewing once they are green.

Review happens in the pull request itself, so watch it for comments. A reviewer may ask what a
change does, ask for a test, or ask you to split the work. Push new commits to the same branch to
answer; there is no need to close the pull request and open another. A member of the team merges the
pull request once review is finished and the checks pass.

## Getting help

- [PIC-SURE User Guide](https://pic-sure.gitbook.io/pic-sure), for using the application
- [PIC-SURE Developer Guide](https://pic-sure.gitbook.io/pic-sure-developer-guide), for
  configuring and running it
- The Issues tab of the repository you are working in
- avillach_lab_developers@googlegroups.com
