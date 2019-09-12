define(["picSure/settings", "handlebars", 'text!overrides/not_authorized.hbs'], function(settings, HBS, notAuthorizedTemplate){
	return {
		/*
		 *
		 * A flag to enable other authentication method, besides the
		 * default, which is AUTH0
		 *
		*/
		idp_provider: 'fence',
		/*
		 * This is the client id
		 */
		fence_client_id: '3YkHUAoPSwaRWzSuNN0DyDbJeU1AxrMVkXBczDo6',
		/*
		 * Unfortunately this is mandatory, and non negotiable :(
		 */
		fence_redirect_url: 'https://datastage-i2b2-transmart-stage.aws.dbmi.hms.harvard.edu/psamaui/login/',
		/*
		 *
		 * Function call to assemble a URL for redirection to the FENCE/Gen3
		 * authentication service.
		 *
		 */
		fence_provider_call: function(override_config) {
			window.location = "https://staging.datastage.io/user/oauth2/authorize"+
				"?response_type=code&scope=user+openid"+
				"&client_id=" + override_config.fence_client_id +
				"&redirect_uri="+override_config.fence_redirect_url;

/*
				$.ajax({
					url: "https://staging.datastage.io/user/oauth2/token",
					type: 'POST',
					headers: {
						"Authorization" : "Basic " + btoa(client_id + ":" + client_secret)
					},
					data: {
						grant_type: "authorization_code",
						code: code,
						redirect_uri: redirect_back_url
					},
					success: function(tokens){
						// This logic should be done on the backend.
						console.log("/user/oauth2/token response...");
						console.log(tokens);

						var id_token = tokens.id_token;
						var access_token = tokens.access_token;
						$('body').append("<pre>"+JSON.stringify(JSON.parse(atob(id_token.split('.')[1])),null, 2)+"</pre>");
					}
				});
*/
		},

		fence_get_user_profile: function(access_token) {
			$.ajax({
				url: "https://staging.datastage.io/user/user",
				type: 'GET',
				headers: {
					"Authorization" : "Bearer " + access_token
				},
				success: function(userProfile){
					console.log("/user/user success response...");
					// The user's roles will come from this reponse.
					$('body').append("<pre>"+JSON.stringify(userProfile)+"</pre>");
				},
				error: function(data) {
					console.log("/user/user error response...");
					console.log(data);
					$('body').append("<div style='color:red'>"+data+"</div>");
				}
			});
		},
		/*
		 * This allows you to build any authorization logic you wish.
		 *
		 * This should be a function that takes the output of common/searchParser/parseQueryString
		 * as the first argument and calls the second argument(a function) passing either true or
		 * false to indicate successful or failed authorization to access the system.
		 *
		 */
		authorization : undefined,
		client_id : settings.client_id,
		/*
		 * This allows you to modify the DOM rendered on the login screen.
		 *
		 * For GRIN this implements a hack that hides the Google button because of
		 * a bug in the Auth0 lock that prevents you from showing only enterprise
		 * buttons.
		 *
		 * Since users still need to pass authorization, there is no harm in
		 * keeping the button hidden, since even if someone decided to show it
		 * they couldn't use it to access the system anyway.
		 */
		postRender: undefined,

        /*
		 * This override allows to configure custom not_authorized page for stack.
		 *
		 * Example configuration: provide custom not_authorized.hbs template in overrides folder and render it similar manner
		 * as login.displayNotAuthorized() function.
		 */
        displayNotAuthorized: undefined
    };
});
