define(['common/session', 'text!settings/settings.json', 'common/searchParser', 'auth0-js', 'jquery', 'handlebars', 'text!login/login.hbs', 'overrides/login', 'util/notification'],
		function(session, settings, parseQueryString, Auth0Lock, $, HBS, loginTemplate, overrides, notification){
	
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
            var redirectURI = window.location.protocol + "//"+ window.location.hostname + (window.location.port ? ":"+window.location.port : "") +"/login";
            if(typeof queryObject.code === "string"){
                $.ajax({
                    url: "/auth/authentication",
                    type: 'post',
                    data: JSON.stringify({
                        code : queryObject.code,
                        redirectURI: redirectURI
                    }),
                    contentType: 'application/json',
                    success: function(data){
                        session.authenticated(data.userId, data.token, data.email, data.permissions);
                        if (sessionStorage.redirection_url)
                            window.location = sessionStorage.redirection_url;
                        else
                            history.pushState({}, "", "userManagement");
                    },
                    error: function(data){
                        notification.showFailureMessage("Failed to authenticate with provider. Try again or contact administrator if error persists.")
                        history.pushState({}, "", "logout");
                    }
                });
            }else{
                var clientId = overrides.client_id ? overrides.client_id : "APy5rn5baqQDfVDiczmjiuIetEIBBU9P";
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
		}
	};
	return login;
});

