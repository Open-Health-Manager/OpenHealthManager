# Open Health Manager™

## About

Open Health Manager™ is the reference implementation for the MITRE Health Manager Lab under the Living Health Lab 
initiative. This project aims to radically flip the locus of health data from organizations
to individuals, promoting individual agency in personal health through primary self-care and 
engagement with their care team. 

A core component needed to effect that flip and enable individual action is a health manager that serves as the
repository for an individual's health data, collecting, combining, and making sense of health data as it is collected
and making it available for the individual to share. The Health Manager lab is working with the HL7™ community
to develop open standards for the collection, representation, and access of health data within a health manager.
Open Health Manager™ is a reference implementation of these standards and a demonstration platform for
what these standards and a patient-controlled health record can enable.

## FHIR Implementation Guides

Open Health Manager™ implements the following FHIR IGs
- [Patient Data Receipt](https://open-health-manager.github.io/patient-data-receipt-ig/) (under development)

## Token Instructions

To use tokens follow the following steps:
1. (If you already have a local file called `pk.txt`, skip this step) In `Account.kt`, uncomment lines 262-265, these lines will create a local file `pk.txt`, this is your private key. Do not upload this file to GitHub! 
2. Run `mvn jetty:run`
3. To log in and receive a token, send a valid username via `http://localhost:8080/fhir/$login` where the body follows the syntax below. After successfully sending this, the response should include a token. This token is created using your private key and is now needed to process a message. 
```
{ "resourceType": "Parameters",
  "parameter": [ {
    "name" : <username> } ]
}
```
4. Send a Bundle as normal to `http://localhost:8080/fhir/$process-message` but now you must provide a parameter where the key is "api_token" and the value is your token from step 3. The message will only process if the token given is associated with the same user as the message. 

Notes:
* The token and private key should not change
* If `pk.txt` is already a file locally, be sure the lines 262-265 in `Account.kt` are commented out as this will give a new private key. 
* DO NOT upload the file `pk.txt` to GitHub, each user should have their own local version!

## Contributing to Open Health Manager™

We love your input! We want to make contributing to this project as easy and transparent as possible, whether it's:

* Reporting a bug
* Discussing the current state of the code
* Submitting a fix
* Proposing new features
* Becoming a maintainer

### We Develop with GitHub

We use GitHub to host code, to track issues and feature requests, as well as accept pull requests.

### We Use [GitHub Flow](https://guides.github.com/introduction/flow/index.html), So All Code Changes Happen Through Pull Requests

Pull requests are the best way to propose changes to the codebase. We actively welcome your pull requests:

* Fork the repo and create your branch from master.
* If you've added code that should be tested, add tests.
* If you've changed APIs, update the documentation.
* Ensure the test suite passes.
* Make sure your code lints.
* Issue that pull request!

### Any contributions you make will be under the Apache 2 Software License

In short, when you submit code changes, your submissions are understood to be under the same Apache 2 license that covers the project. Feel free to contact the maintainers if that's a concern.

### Report bugs using GitHub's issues

We use GitHub issues to track public bugs. Report a bug by opening a new issue it's that easy!

### Write bug reports with detail, background, and sample code

Great Bug Reports tend to have:

* A quick summary and/or background
* Steps to reproduce
* Be specific!
* Give sample code if you can.
* What you expected would happen
* What actually happens
* Notes (possibly including why you think this might be happening, or stuff you tried that didn't work)
* People love thorough bug reports. I'm not even kidding.

# License
Copyright 2022 The MITRE Corporation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.