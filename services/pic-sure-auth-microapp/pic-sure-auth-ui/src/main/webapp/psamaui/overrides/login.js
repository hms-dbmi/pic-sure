define(["picSure/settings", "handlebars", 'text!overrides/not_authorized.hbs'], function(settings, HBS, notAuthorizedTemplate){
	return {
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
