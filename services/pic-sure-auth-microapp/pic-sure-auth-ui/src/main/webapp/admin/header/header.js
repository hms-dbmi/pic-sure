define(["backbone","handlebars", "text!header/header.hbs"], 
		function(BB, HBS, template){
	var headerView = BB.View.extend({
		initialize : function(){
			this.template = HBS.compile(template);
		},
        events : {
            "click #logout-btn" : "gotoLogin"
        },
        gotoLogin : function(event){
            this.logout();
            window.location='/';
        },
		logout : function(event){
            sessionStorage.clear();
		}, 
		render : function(){
			this.$el.html(this.template({}));
		}
	});

	return {
		View : new headerView({})
	};
});