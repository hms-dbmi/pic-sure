# PIC-SURE Auth MicroApp

### Local Development
At present, local development is conducted using our [All-In-One](https://github.com/hms-dbmi/pic-sure-all-in-one) Virtual Box. We offer implementations for both [CentOS](https://github.com/hms-dbmi/pic-sure-all-in-one/tree/master) and [Redhat](https://github.com/hms-dbmi/pic-sure-all-in-one/tree/feature/redhat)  based operating systems. To get started, please consult the [README](https://github.com/hms-dbmi/pic-sure-all-in-one/tree/master#readme) section of the [All-In-One](https://github.com/hms-dbmi/pic-sure-all-in-one) repository.

#### To add an initial top admin user in the system
If you follow the steps above, spins up the docker containers and you can see the login page in the browser, you are almost there.

You just need to add a top admin user in the system to be able to play with all features.

There is a sql script in source code for adding the top admin user with some initial setup, you can import the script
into your database.

Open the file under `/{{root_pic-sure-auth-microapp}}/pic-sure-auth-db/db/tools/first_time_run_the_system_and_insert_admin_user.sql`,
modify the configuration data - `@user_email` with your own google email

#### Terms of Service

If a user logging in has not accepted the latest terms of service, they will be directed to the 'Accept Terms of Service' page.
The content of the terms of service is stored in the termsOfService table in the database.  This is html that is rendered on the page.  
To trigger the acceptance of the terms of service, this html must include a button with class 'accept-tos-button'.  Anything with this class,
upon clicking, will register the logged in user as accepting terms of service.  This button can be disabled until criteria are met.  Some example termsOfService content would be:



```aidl
These are the terms of service.
<br>
<input type="checkbox" id="checkMe">This box must be checked</input>
<br>
<button type="button" disabled id="acceptBtn" class="btn btn-info accept-tos-button">
  <span>Accept</span> 
</button>
<script>
 $('#checkMe').on('change', function(){
	$('#acceptBtn').prop("disabled", !this.checked);
	});
</script>
```

#### Email templates configuration

PSAMA has mechanism of sending various emails to the users and admins depending of the action.
MailService.class is responsible for compiling Mustache templates and populating it with required information based on parameters. There are few settings configured in standalone.xml for that.

Email Template Path is where email templates could be stored, so that it can be configured per stack if needed.
```
<simple name="java:global/templatePath" value="${env.TEMPLATE_PATH:/usr/local/shared/applications/}"/>
```
Email Template Path can be mapped as a volume in a container, so that application can discover it, where directory matching the one from standalone.xml:
```    
volumes:
    - $PWD/config/psama/emailTemplates:/usr/local/shared/applications
```

Denied Email Enabled is flag to enable sending email to admin if user has not been added to system and trying to login.
```
<simple name="java:global/deniedEmailEnabled" value="${env.DENIED_EMAIL_ENABLED:true}"/>
```

List of admin email configured as below. Provide comma separated list of admin.
```
<simple name="java:global/adminUsers" value="${env.COMMA_SEPARATED_EMAILS}"/>
```
