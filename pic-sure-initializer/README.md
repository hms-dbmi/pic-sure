This project creates a resource and user initialization command line application.

Getting started:

mvn clean install assembly:single
cd target
java -jar pic-sure-initializer-2.0.0-SNAPSHOT-jar-with-dependencies.jar


Follow the instructions given in the usage output.

Example users.json:

[
    {
        "userId":"foobar@bar.com",
        "subject":"foobar@bar.com",
        "roles":"SYSTEM_USER"
    }
]

Example resources.json:

[
    {
        "name": "Foo Resource",
        "description": "This is the Foo resource. The data in this resource was loaded using the Foo loader from the base files obtained from http://foo.project/foodata after curation by Foo-cleaner.",
        "baseUrl": "http://localhost:8081/i-pity-da-foo",
        "token":"foo"
    }
]


