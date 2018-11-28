define(["backbone", "handlebars", "picSure/privilegeFunctions", "privilege/privilegeManagement", "text!privilege/addPrivilege.hbs", "text!privilege/addPrivilegeConnectionForm.hbs", "picSure/privilegeFunctions"],
		function(BB, HBS, privilegeFunctions, privilegeManagement, template, connectionTemplate, privilegeFunctions){
	var view = BB.View.extend({
		initialize: function(opts){
			this.privileges = opts.privileges;
			this.template = HBS.compile(template);
			this.managementConsole = opts.managementConsole;


		},
		events: {
			"click #save-privilege-button": "createPrivilege"
		},
		createPrivilege: function(event){
			var metadata = {};

			var privilege = {
				uuid : $('#new-privilege-form input[name=privilege_name]').attr('uuid'),
				name : $('#new-privilege-form input[name=privilege_name]').val(),
				description : $('#new-privilege-form input[name=privilege_description]').val()
			};
			privilegeFunctions.createOrUpdatePrivilege(
				[privilege],
				"POST",
				function(result){
					console.log(result);
                    this.managementConsole.render();
				}.bind(this)
			);
		},
		render: function(){
			this.$el.html(this.template({privileges:this.privileges}));
			// this.renderConnectionForm({target:{value:this.connections[0].id}})
		}
	});
	return view;
});