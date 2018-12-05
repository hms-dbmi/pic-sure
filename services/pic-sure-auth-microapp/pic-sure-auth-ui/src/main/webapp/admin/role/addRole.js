define(["backbone", "handlebars", "picSure/roleFunctions", "role/roleManagement", "text!role/addRole.hbs"],
		function(BB, HBS, roleFunctions, roleManagement, template){
	var view = BB.View.extend({
		initialize: function(opts){
			this.privileges = opts.privileges;
			this.template = HBS.compile(template);
			this.managementConsole = opts.managementConsole;
		},
		events: {
			"click #save-role-button": "createRole"
		},
		createRole: function(event){
            var privileges = [];
            _.each(this.$('input:checked'), function(element) {
                privileges.push({uuid: element.value});
            });

			var metadata = {};
			var role = {
				uuid : $('#new-role-form input[name=role_name]').attr('uuid'),
				name : $('#new-role-form input[name=role_name]').val(),
				description : $('#new-role-form input[name=role_description]').val(),
				privileges: privileges
			};
			roleFunctions.createOrUpdateRole(
				[role],
				"POST",
				function(result){
					console.log(result);
                    this.managementConsole.render();
				}.bind(this)
			);
		},
		render: function(){
			this.$el.html(this.template({privileges:this.privileges}));
		}
	});
	return view;
});