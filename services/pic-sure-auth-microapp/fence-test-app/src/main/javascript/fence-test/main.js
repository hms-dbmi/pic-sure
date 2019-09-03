require.config({
	baseUrl: "/fence-test/",
	paths: {
		jquery: 'webjars/jquery/3.4.1/dist/jquery.min',
		autocomplete: 'webjars/devbridge-autocomplete/1.4.7/dist/jquery.autocomplete',
		underscore: 'webjars/underscorejs/1.8.3/underscore-min',
		handlebars: 'webjars/handlebars/1.8.3/underscore-min',
		bootstrap: 'webjars/bootstrap/3.3.7-1/js/bootstrap.min',
		bootstrapStyles: 'webjars/bootstrap/3.3.7-1/css/bootstrap.min.css',
		backbone: 'webjars/backbonejs/1.3.3/backbone-min',
		text: 'webjars/requirejs-text/2.0.15/text',
		handlebars: 'webjars/handlebars/4.0.5/handlebars.min',
		treeview: 'webjars/bootstrap-treeview/1.2.0/bootstrap-treeview.min',
		treeviewStyles: 'webjars/bootstrap-treeview/1.2.0/bootstrap-treeview.min.css',
		'auth0-js': "webjars/auth0.js/9.2.3/build/auth0",
		Noty: 'webjars/noty/3.1.4/lib/noty',
		NotyStyles: 'webjars/noty/3.1.4/lib/noty.css',
	},
	shim: {
		"bootstrap": {
			deps: ["jquery"]
		},
		"treeview": {
			deps:["bootstrap"]
		},
		"auth0-js": {
			deps:["jquery"],
			exports: "Auth0Lock"
		},
		"common/startup": {
			deps:["overrides/main"]
		}
	}
});

require(["startup"], function(startup){
	startup();
});
