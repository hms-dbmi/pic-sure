define(['common/session', 'text!settings/settings.json', 'common/searchParser', 'auth0-js', 'jquery', 'handlebars', 'text!login/login.hbs', 'overrides/login'],
		function(session, settings, parseQueryString, Auth0Lock, $, HBS, loginTemplate, overrides){
	
	var loginTemplate = HBS.compile(loginTemplate);

	var loginCss = null
	$.get("https://avillachlab.us.webtask.io/connection_details_base64?webtask_no_cache=1&css=true", function(css){
		loginCss = "<style>" + css + "</style";
	});

	var login = {
		showLoginPage : function(){
            var queryObject = parseQueryString();


            if(typeof queryObject.code === "string"){
                session.authenticated('', 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0QGVtYWlsLmNvbSIsImlzcyI6ImJhciIsImV4cCI6MTU0ODAxMjY2MywiaWF0IjoxNTM5MzcyNjYzLCJqdGkiOiJGb28iLCJlbWFpbCI6InRlc3RAZW1haWwuY29tIn0.1_a8nsx-fkTkYFXL0AQ3rcnMOBVWk6-KlcMNV-oiXi0', 'alex@alex.com', '');
            	// history.pushState({}, "", "userManagement");
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
                       alert("Can't login");
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

            //
			// var queryObject = parseQueryString();
			// if(queryObject.id_token){
			// 	var expiresAt = JSON.stringify(
			// 			queryObject.expires_in * 1000 + new Date().getTime()
			// 		);
			// 	localStorage.setItem('access_token', queryObject.access_token);
			// 	localStorage.setItem('id_token', queryObject.id_token);
			// 	localStorage.setItem('expires_at', expiresAt);
			// }
			//
			// if(typeof overrides.authorization === "function"){
			// 	overrides.authorization(queryObject.id_token, handleAuthorizationResult);
			// } else {
			// 	if(queryObject.id_token){
			// 		defaultAuthorizationCheck(queryObject.id_token, handleAuthorizationResult);
			// 	}else{
			// 		handleAuthorizationResult(false);
			// 	}
			// }
		}
	};
	return login;
});

