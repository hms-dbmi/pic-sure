define(["backbone","handlebars", "text!header/header.hbs"], 
		function(BB, HBS, template){
	var headerView = BB.View.extend({
		initialize : function(){
			this.template = HBS.compile(template);
		},
		events : {
			"click #logout-btn" : "logout"
		},
		logout : function(event){
			localStorage.clear();
			window.location = "/"
		}, 
		render : function(){
			this.$el.html(this.template({}));
		}
	});

	return {
		View : new headerView({})
	};
});