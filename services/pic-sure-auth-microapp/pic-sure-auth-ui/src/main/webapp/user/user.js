define(["backbone","handlebars", "text!user/user.hbs"],
		function(BB, HBS, template){
    var userModel = BB.Model.extend({
    });
	var userView = BB.View.extend({
		initialize : function(opts){
			this.template = HBS.compile(template);
			this.userManagementView = opts.userManagementView;
		},
        className: "user-details",
		events : {
			"click .userId" : "showUserInfo"
		},
        showUserInfo: function (e) {
            this.userManagementView.showUser(this.model.uuid);
        },
		render : function(){
            this.$el.html(this.template(this.model));
		}
	});

	return {
		View : userView,
        Model : userModel
	};
});