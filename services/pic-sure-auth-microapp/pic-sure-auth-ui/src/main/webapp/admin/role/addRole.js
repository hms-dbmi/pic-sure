define(["backbone", "handlebars", "picSure/roleFunctions", "role/roleManagement", "text!role/addRole.hbs", "text!role/addRoleConnectionForm.hbs", "picSure/privilegeFunctions"],
		function(BB, HBS, roleFunctions, roleManagement, template, connectionTemplate, privilegeFunctions){
	var view = BB.View.extend({
		initialize: function(opts){
			this.privileges = opts.privileges;
			this.template = HBS.compile(template);
			this.managementConsole = opts.managementConsole;


		},
		events: {
			// "change #new-role-connection-dropdown":"renderConnectionForm",
			"click #save-role-button": "createRole"
		},
		createRole: function(event){
			var metadata = {};
			// _.each($('#current-connection-form input[type=text]'), function(entry){
			// metadata[entry.name] = entry.value});
			var role = {
				uuid : $('#new-role-form input[name=role_name]').attr('uuid'),
				name : $('#new-role-form input[name=role_name]').val(),
				description : $('#new-role-form input[name=role_description]').val()
				// connectionId: $('#new-role-connection-dropdown').val(),
				// generalMetadata:JSON.stringify(metadata),
				// roles: _.pluck(this.$('input:checked'),'value').join(',')
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
		// renderConnectionForm: function(event){
		// 	this.connection = _.find(this.connections, {id:event.target.value});
		// 	roleFunctions.getAvailableRoles(function (roles) {
		// 		$('#current-connection-form', this.$el).html(
		// 				this.connectionTemplate({
		// 						connection: this.connection,
		// 						createOrUpdateRole: true,
		// 						availableRoles: roles
		// 				}));
         //    }.bind(this));
		// },
		render: function(){
			this.$el.html(this.template({privileges:this.privileges}));
			// this.renderConnectionForm({target:{value:this.connections[0].id}})
		}
	});
	return view;
});