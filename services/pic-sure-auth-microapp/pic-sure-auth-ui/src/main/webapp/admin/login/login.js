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
            if(typeof queryObject.code === "string"){
                $.ajax({
                    url: "/auth/authentication",
                    type: 'post',
                    data: JSON.stringify({
                        code : queryObject.code,
                        redirectURI: window.location.protocol + "//"+ window.location.hostname + (window.location.port ? ":"+window.location.port : "") +"/login"
                    }),
                    contentType: 'application/json',
                    success: function(data){
                        session.authenticated(data.userId, data.token, data.email, data.permissions);
                        history.pushState({}, "", "userManagement");
                    },
                    error: function(data){
                        notification.showFailureMessage("Failed to authenticate with provider. Try again or contact administrator if error persists.")
                        history.pushState({}, "", "logout");
                    }
                });
            }else{
                var clientId = overrides.client_id ? overrides.client_id : "ywAq4Xu4Kl3uYNdm3m05Cc5ow0OibvXt";
                $.ajax("https://avillachlab.us.webtask.io/connection_details_base64/?webtask_no_cache=1&client_id=" + clientId,
                {
                    dataType: "text",
                    success : function(scriptResponse){
                        $('#main-content').html(loginTemplate({
                            buttonScript : scriptResponse,
                            clientId : clientId,
                            auth0Subdomain : "avillachlab",
                            callbackURL : window.location.protocol + "//"+ window.location.hostname + (window.location.port ? ":"+window.location.port : "") +"/login"
                        }));
                        $('#main-content').append(loginCss);
                    }
                });
            }
		}
	};
	return login;
});

