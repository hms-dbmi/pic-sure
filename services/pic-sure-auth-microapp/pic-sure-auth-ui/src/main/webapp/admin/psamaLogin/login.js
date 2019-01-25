define(['common/session', 'picSure/settings', 'common/searchParser', 'auth0-js', 'jquery', 'handlebars', 'text!psamaLogin/login.hbs', 'text!login/not_authorized.hbs', 'overrides/login', 'util/notification', 'picSure/settings'],
		function(session, settings, parseQueryString, Auth0Lock, $, HBS, loginTemplate, notAuthorizedTemplate, overrides, notification){
	
	var loginTemplate = HBS.compile(loginTemplate);

	var loginCss = null
	$.get("https://avillachlab.us.webtask.io/connection_details_base64?webtask_no_cache=1&css=true", function(css){
		loginCss = "<style>" + css + "</style";
	});

	var login = {
		showLoginPage : function(){
            var queryObject = parseQueryString();
            if (queryObject.redirection_url) sessionStorage.redirection_url = queryObject.redirection_url.trim();
            if (queryObject.not_authorized_url) sessionStorage.not_authorized_url = queryObject.not_authorized_url.trim();
            var redirectURI = window.location.protocol
                            + "//"+ window.location.hostname
                            + (window.location.port ? ":"+window.location.port : "")
                            + (window.location.port ? ":"+window.location.port : "")
                            + settings.uiPath + "/login";
            if(typeof queryObject.code === "string"){
                $.ajax({
                    url: settings.servicesPath + '/authentication',
                    type: 'post',
                    data: JSON.stringify({
                        code : queryObject.code,
                        redirectURI: redirectURI
                    }),
                    contentType: 'application/json',
                    success: function(data){
                        session.authenticated(data.userId, data.token, data.email, data.permissions, data.acceptedTOS, this.handleNotAuthorizedResponse);
                        if (!data.acceptedTOS){
                            history.pushState({}, "", "tos");
                        } else {
                            if (sessionStorage.redirection_url) {
                                window.location = sessionStorage.redirection_url + "?token=" + JSON.parse(sessionStorage.session).token;
                            }
                            else {
                                history.pushState({}, "", "userManagement");
                            }
                        }
                    },
                    error: function(data){
                        notification.showFailureMessage("Failed to authenticate with provider. Try again or contact administrator if error persists.")
                        history.pushState({}, "", "logout");
                    }
                });
            }else{
                if (!overrides.client_id){
                    notification.showFailureMessage("Client_ID is not provided. Please update overrides/login.js file.");
                }
                var clientId = overrides.client_id;
                $.ajax("https://avillachlab.us.webtask.io/connection_details_base64/?webtask_no_cache=1&client_id=" + clientId,
                {
                    dataType: "text",
                    success : function(scriptResponse){
                        $('#main-content').html(loginTemplate({
                            buttonScript : scriptResponse,
                            clientId : clientId,
                            auth0Subdomain : "avillachlab",
                            callbackURL : redirectURI
                        }));
                        $('#main-content').append(loginCss);
                    }
                });
            }
		},
        handleNotAuthorizedResponse : function () {
            if (JSON.parse(sessionStorage.session).token) {
                if (sessionStorage.not_authorized_url)
                    window.location = sessionStorage.not_authorized_url;
                else
                    $('#main-content').html(HBS.compile(notAuthorizedTemplate)(settings));
            }
            else {
                window.location = (window.location.pathname.split('/').length > 1 ? "/"+window.location.pathname.split('/')[1] : "") + "/logout";
            }
        }
	};
	return login;
});

