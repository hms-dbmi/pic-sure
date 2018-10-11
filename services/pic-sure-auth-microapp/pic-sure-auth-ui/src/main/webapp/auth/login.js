define(['common/session', 'text!auth/not_authorized.hbs', 'text!settings/settings.json', 'common/searchParser', 'auth0-js', 'jquery', 'handlebars', 'text!auth/login.hbs', 'overrides/login', 'header/header', 'user/userManagement'],
		function(session,  notAuthorizedTemplate, settings, parseQueryString, Auth0Lock, $, HBS, loginTemplate, overrides, header, userManagement){
	
	var loginTemplate = HBS.compile(loginTemplate);

	var loginCss = null
	$.get("https://avillachlab.us.webtask.io/connection_details_base64?webtask_no_cache=1&css=true", function(css){
		loginCss = "<style>" + css + "</style";
	});
	
	var defaultAuthorizationCheck = function(id_token, callback){
		var deferredArray = [];
		for(var resourceIndex in resourceMeta){
			var resource = resourceMeta[resourceIndex];
			var resourceDeferred = $.Deferred();
            deferredArray.push(resourceDeferred);

		}
		$.when.apply($, deferredArray).then(function(authorizationDecisions){
			callback(authorizationDecisions);
		});
	};
	
	var handleAuthorizationResult = function(userIsAuthorized){
		var queryObject = parseQueryString();
		if(userIsAuthorized && typeof queryObject.access_token === "string" && typeof queryObject.id_token === "string"){
			window.location = "/";
		}else{
			if(typeof queryObject.access_token === "string"){
				$('#main-content').html(HBS.compile(notAuthorizedTemplate)(JSON.parse(settings)));
			}else{
				var clientId = overrides.client_id ? overrides.client_id : "ywAq4Xu4Kl3uYNdm3m05Cc5ow0OibvXt";
				$.ajax("https://avillachlab.us.webtask.io/connection_details_base64/?webtask_no_cache=1&client_id=" + clientId, 
					{
						dataType: "text",
						success : function(scriptResponse){
							var script = scriptResponse.replace('responseType : "code"', 'responseType : "token"');
							$('#main-content').html(loginTemplate({
								buttonScript : script,
								clientId : clientId,
								auth0Subdomain : "avillachlab",
								callbackURL : window.location.protocol + "//"+ window.location.hostname + (window.location.port ? ":"+window.location.port : "") +"/login"
							}));
							overrides.postRender ? overrides.postRender.apply(this) : undefined;
							$('#main-content').append(loginCss);
					}
				});					
			}
		}
	}

	var login = {
		showLoginPage : function(){
            var queryObject = parseQueryString();


            if(typeof queryObject.code === "string"){
                session.authenticated('', 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGVrc2FuZC5uaWtpdGluQGNoaWxkcmVucy5oYXJ2YXJkLmVkdSIsImlzcyI6ImJhciIsImV4cCI6MTU0MTE5Mjc1MSwiaWF0IjoxNTMyNTUyNzUxLCJqdGkiOiJGb28iLCJlbWFpbCI6ImFsZWtzYW5kLm5pa2l0aW5AY2hpbGRyZW5zLmhhcnZhcmQuZWR1In0.JjsAojQr-k8oxBC5u2jBtM03ljpWG0GejrNu81GGk-c', 'alex@alex.com', '');
            	history.pushState({}, "", "userManagement");
                // $.ajax({
                 //    url: "/auth/authentication",
                 //    type: 'post',
                 //    data: JSON.stringify({
                 //        code : queryObject.code,
                 //        redirect_uri: window.location.protocol + "//"+ window.location.hostname + (window.location.port ? ":"+window.location.port : "") +"/login"
                 //    }),
                 //    contentType: 'application/json',
                 //    success: function(data){
                 //        session.authenticated(data.userId, data.token, data.username, data.permissions);
                 //        history.pushState({}, "", "userManagement");
                 //    },
                 //    error: function(data){
                 //       alert("Can't login");
                 //    }
                // });
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

