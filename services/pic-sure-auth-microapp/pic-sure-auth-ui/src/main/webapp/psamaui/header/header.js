define(["backbone","handlebars", "text!header/header.hbs", "common/session", "picSure/userFunctions"],
		function(BB, HBS, template, session, userFunctions){
	var headerView = BB.View.extend({
		initialize : function(){
			HBS.registerHelper('not_contains', function(array, object, opts) {
				var found = _.find(array, function(element){
					return (element === object);
				});
				if (found)
					return opts.inverse(this);
				else
					return opts.fn(this);
			});
			this.template = HBS.compile(template);
		},
		events : {
			"click #logout-btn" : "gotoLogin"
		},
		gotoLogin : function(event){
			this.logout();
			window.location="/psamaui/login" + window.location.search;
		},
		logout : function(event){
			sessionStorage.clear();
		},
		render : function(){
			if(window.location.pathname!=="/psamaui/tos"){
				userFunctions.me(this, function(user){
					this.$el.html(this.template({
						privileges: user.privileges
					}));
				}.bind(this));				
			}
		}
	});

	return {
		View : new headerView({})
	};
});