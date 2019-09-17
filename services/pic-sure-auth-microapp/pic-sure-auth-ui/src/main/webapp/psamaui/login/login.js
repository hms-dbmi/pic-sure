define(['common/session', 'picSure/settings', 'common/searchParser', 'jquery', 'handlebars', 'text!login/login.hbs', 'text!login/not_authorized.hbs', 'overrides/login', 'util/notification', 'login/fence_login'],
		function(session, settings, parseQueryString, $, HBS, loginTemplate, notAuthorizedTemplate, overrides, notification, fenceLogin){

	var loginTemplate = HBS.compile(loginTemplate);

	var loginCss = null
	$.get("https://avillachlab.us.webtask.io/connection_details_base64?webtask_no_cache=1&css=true", function(css){
		loginCss = "<style>" + css + "</style";
	});

	var login = {
		showLoginPage : function(){
			if (overrides.idp_provider == 'fence') {

                //var code = window.location.search.slice(6);

                // Check if the `code` parameter is set in the URL, as it would be, when
                // FENCE redirects back after authentication.
                var queryString = window.location.search.substring(1);
                var params = {}, queries, temp, i, l;
                // Split into key/value pairs
                queries = queryString.split("&");
                // Convert the array of strings into an object
                for ( i = 0, l = queries.length; i < l; i++ ) {
                    temp = queries[i].split('=');
                    params[temp[0]] = temp[1];
                }
                var code = params['code'];
                if (code) {
                    console.log('showLoginPage() redirecting to fence-authentication, with code from FENCE');
                    $('#main-content').html('Got a code back: '+code);

                    // Use the `code` received from FENCE to call the PSAMA back-end service, to retrieve the
                    // user information, based on the code. User information will be stored in the browser's
                    // session storage, for later use. Note: The response will be PSAMA specific information,
                    // and NOT FENCE information
                    $.ajax({
                        url: '/psama/fence-authentication',
                        type: 'post',
                        data: JSON.stringify({
                            "code":code
                        }),
                        contentType: 'application/json',
                        success: function(data){
                            console.log('showLoginPage() psama fence-authentication is successful.');
                            console.log(data);

                            // If back-end response is success, we will get a PSAMA JWT token back, and some
                            // other information. We will set the session variables for the user with our own
                            // internal expiry, and other claims.
                            session.authenticated(
                                data.userId,
                                data.token,
                                data.email,
                                data.permissions,
                                data.acceptedTOS,
                                this.handleNotAuthorizedResponse
                            );
                            if (data.acceptedTOS !== 'true'){
                                history.pushState({}, "", "/psamaui/tos");
                            } else {
                                if (sessionStorage.redirection_url) {
                                    window.location = sessionStorage.redirection_url;
                                } else {
                                    history.pushState({}, "", "/psamaui/userManagement");
                                }
                            }

                            // Once all the session information is stored in the browser, we can redirect to the
                            // real destination
                            console.log('showLoginPage() redirecting to /picsureui page...');
                            window.location = '/picsureui';

                        }.bind(this),
                        error: function(data){
                            console.log("showLoginPage() could not authenticate with the FENCE IDP");
                            console.log(data);

                            notification.showFailureMessage("Failed to authenticate with DataStage FENCE protocol. "+
                                "Try again or contact administrator if error persists.");
                            history.pushState(
                                {},
                                "",
                                sessionStorage.not_authorized_url? sessionStorage.not_authorized_url : "/psamaui/not_authorized?redirection_url=/picsureui"
                            );
                        }
                    });
                    console.log("showLoginPage() returning null?!");
                    return null;
                } else {
                    // This is the initial login, when there is no code present
                    $('#main-content').html('FENCE configuration is found in the `overrides/login.js` file. ');
                    overrides.fence_provider_call(overrides);
                    return null;
                }
			} else {
				$('#main-content').html('This branch is for FENCE authentication only. Please change or set the `idp_provider` field in `overrides/login.js` file. ');
				return null;
			}
            var queryObject = parseQueryString();
            if (queryObject.redirection_url) sessionStorage.redirection_url = queryObject.redirection_url.trim();
            if (queryObject.not_authorized_url) sessionStorage.not_authorized_url = queryObject.not_authorized_url.trim();
            var redirectURI = window.location.protocol
                            + "//"+ window.location.hostname
                            + (window.location.port ? ":"+window.location.port : "")
                            + "/psamaui/login/";
            if(typeof queryObject.access_token === "string"){
                $.ajax({
                    url: '/psama/authentication',
                    type: 'post',
                    data: JSON.stringify({
                        access_token : queryObject.access_token,
                        redirectURI: redirectURI
                    }),
                    contentType: 'application/json',
                    success: function(data){
                        session.authenticated(data.userId, data.token, data.email, data.permissions, data.acceptedTOS, this.handleNotAuthorizedResponse);
                        if (data.acceptedTOS !== 'true'){
                            history.pushState({}, "", "/psamaui/tos");
                        } else {
                            if (sessionStorage.redirection_url) {
                                window.location = sessionStorage.redirection_url;
                            }
                            else {
                                history.pushState({}, "", "/psamaui/userManagement");
                            }
                        }
                    }.bind(this),
                    error: function(data){
                        notification.showFailureMessage("Failed to authenticate with provider. Try again or contact administrator if error persists.")
                        history.pushState({}, "", sessionStorage.not_authorized_url? sessionStorage.not_authorized_url : "/psamaui/not_authorized?redirection_url=/picsureui");
                    }
                });
            }else{
                if (!overrides.client_id){
                    notification.showFailureMessage("Client_ID is not provided. Please update overrides/login.js file.");
                }
                var clientId = overrides.client_id;

                if (settings.customizeAuth0Login){
                    require.config({
                        paths: {
                            'auth0-js': "webjars/auth0.js/9.2.3/build/auth0"
                        },
                        shim: {
                            "auth0-js": {
                                deps:["jquery"],
                                exports: "Auth0Lock"
                            }
                        }
                    });
                    require(['auth0-js'], function(){
                        $.ajax("https://avillachlab.us.webtask.io/connection_details_base64/?webtask_no_cache=1&client_id=" + clientId,
                        {
                            dataType: "text",
                            success : function(scriptResponse){
                                scriptResponse = scriptResponse.replace("responseType : \"code\"","responseType : \"token\"");
                                $('#main-content').html(loginTemplate({
                                    buttonScript : scriptResponse,
                                    clientId : clientId,
                                    auth0Subdomain : "avillachlab",
                                    callbackURL : redirectURI
                                }));
                                overrides.postRender ? overrides.postRender.apply(this) : undefined;
                                $('#main-content').append(loginCss);
                            }
                        })
                    });


                } else {
                    require.config({
                        paths: {
                            'auth0Lock': "webjars/auth0-lock/11.2.3/build/lock",
                        },
                        shim: {
                            "auth0Lock": {
                                deps:["jquery"],
                                exports: "Auth0Lock"
                            }
                        }
                    });
                    require(['auth0Lock'], function(Auth0Lock){
                        var lock = new Auth0Lock(
                            clientId,
                            settings.auth0domain + ".auth0.com",
                            {
                                auth: {
                                    redirectUrl: redirectURI,
                                    responseType: 'token',
                                    params: {
                                        scope: 'openid email' // Learn about scopes: https://auth0.com/docs/scopes
                                    }
                                }
                            }
                        );
                        lock.show();
                    });
                }
            }
		},
        handleNotAuthorizedResponse : function () {
            if (JSON.parse(sessionStorage.session).token) {
                if (sessionStorage.not_authorized_url)
                    window.location = sessionStorage.not_authorized_url;
                else
                    window.location = "/psamaui/not_authorized" + window.location.search;
            }
            else {
                window.location = "/psamaui/logout" + window.location.search;
            }
        },
        displayNotAuthorized : function () {
            if (overrides.displayNotAuthorized)
                overrides.displayNotAuthorized()
            else
                $('#main-content').html(HBS.compile(notAuthorizedTemplate)({helpLink:settings.helpLink}));
        }
    };
	return settings.fenceEnabled ? fenceLogin : login;
});
